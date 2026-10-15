# ADR 0001: Orchestrated transactions with declarative flows

Status: accepted (decision 2 interpretation pending confirmation, see below)
Date: 2026-10-15

## Context

The domain is money / inventory / legal records, so a transaction left
half-done is unacceptable. Observed needs:

- The flow varies per transaction (routing depends on payload and results).
- Journeys need parallel fan-out/join and branching on results.
- Operators must be able to ask "where is transaction X right now?".
- The flow must be changeable without redeploying every subsystem.
- Subsystems should eventually scale independently / across nodes.

## Decision

1. **Orchestration, not choreography.** One transaction actor per
   transaction owns the journey. Subsystems are participants that react to
   commands; they do not know the flow.
2. **Flows are declarative data** (validated into structs at load time):
   steps, dependencies (fan-out/join), branch rules on results, and a
   compensation per step. The transaction process interprets the data; it
   contains no hardcoded subsystem names.
3. **Availability over consistency ("keep going").** Every transaction starts
   with a valid baseline outcome and may only ever be replaced by a better
   valid outcome. A failure or partition leaves the best outcome found so far.
4. **Scope: single node first.** Distribution is deferred but must not be
   designed out (logical subsystem addressing, no node-local assumptions in
   the message contracts).
5. **Quality gates:** 100% automated unit-test coverage; >85% automated
   integration-test coverage.

## Interpretation of decision 2 (please confirm)

- Each flow defines a **baseline outcome** produced at `queued` without
  contacting any subsystem.
- Each step may yield a **candidate outcome**; the flow supplies an ordering
  (`better_than`). A candidate replaces the current outcome only if better.
- Because every intermediate outcome is valid by construction, a transaction
  that exhausts retries or loses a participant **terminates with its best
  outcome** rather than rolling everything back. Compensation is still
  required where a completed step's visible effect makes the outcome worse
  than the baseline.
- Constraint this puts on subsystems: **every step's effect must itself leave
  the system in a valid state**. If a real step cannot guarantee that, it
  needs a compensation and a stricter flow.
- Under a future partition both sides may proceed; convergence relies on
  idempotent effects plus the deterministic `better_than` ordering (keep the
  maximum).

## Consequences

- `Transaction` becomes a flow interpreter plus an append-only event log.
- Subsystem contract: command carries `tx_id`, `step_id`, `attempt`; reply is
  `ok(candidate)`, `rejected(reason)` (business) or `error(reason)` (retry).
- Idempotency must be durable in subsystems, not an in-memory map.
- The term-to-file `Store` is a spike artefact. A real transactional store
  (outbox/inbox pattern) is required before production use.
- Ordering semantics ("better than") become part of the flow schema and need
  property-based tests.

---

## Amendment 1 (2026-10-15): the real domain is provider selection, not a sequence

Decision 2 was clarified. The "subsystems" are **facades over unreliable
third-party providers** (initial use case: FX swap + idempotent payment).
Providers may be fast and cheap, slow, expensive, degraded, stalled, or change
their behaviour without notice. We have no control over them.

This changes the shape of a flow from "A then B" to:

```text
received → quoting → selecting → committing → settled
                │                    │
   (fan-out to N providers,          └─ timeout/unknown → in_doubt → reconciling → settled | failed
    deadline, collect quotes)
```

### Principles

1. **Quote freely, commit once.** Fan-out/hedging is safe for quotes (no
   effect). Execution has exactly one commit point per transaction at a time.
   "Keep going" (decision 2) applies to quoting/selection and to choosing the
   next provider *after a definite failure*.
2. **A timeout on commit is not a failure, it is `in_doubt`.** Before trying
   another provider we must reconcile the first (query by idempotency key) or
   rely on a provider-honoured idempotency key. Never double-pay.
3. **Best-outcome-so-far lives in the pre-commit phase.** The baseline is a
   valid default route (e.g. last known-good provider); quotes can only improve
   it, judged by a scoring policy. After commit we only reconcile.
4. **Providers are modelled, not trusted.** Each has our view of its state
   (`healthy | degraded | stalled | unknown | quarantined`), observed version /
   payload fingerprint, rolling latency and error rate, and cost basis.
5. **A sentinel keeps the model honest**: scheduled probes (configurable
   interval, e.g. 6h) plus passive observation of live traffic, detecting
   version/shape drift, latency drift and availability, feeding a circuit
   breaker.
6. **Scoring is policy as data**: weights/constraints over cost, latency,
   reliability and quote validity window; hard constraints (e.g. quote must not
   expire before commit).
7. **Resource budgets are first-class**: bounded in-flight work per provider
   (bulkhead), bounded mailboxes, log compaction/retention, telemetry on CPU,
   memory, storage and network. Hedging has a network/rate-limit cost and is
   budgeted.

### Provider adapter contract (behaviour)

- `quote(request)` -> `{:ok, quote}` | `{:rejected, why}` | `{:error, transient}` | `:timeout`
- `execute(quote, idempotency_key)` -> `{:ok, receipt}` | `{:rejected, why}` | `{:error, transient}` | `:in_doubt`
- `status(idempotency_key)` -> `{:ok, receipt}` | `:not_found` | `:unknown`
- `probe()` -> version / up-state / latency sample

### Open questions

- Do providers support lookup by idempotency key (resolves `in_doubt`)?
- Are quotes firm with an expiry, or indicative?
- Is the scoring objective global or per customer/request?
- Are the FX and payment legs one provider call or two (second commit point)?

---

## Amendment 2 (2026-10-15): lifecycle, cascading policy, settlement deadlines

Answers to the open questions:

- Providers **can** look up a payment by idempotency key, but not in real time
  yet. `in_doubt` resolution therefore needs an asynchronous reconcile path
  (poll/schedule) for now; revisit later.
- Quotes are **firm with an expiry**: expiry is a hard constraint on every
  later step.
- The FX leg and the payment leg are **separate provider calls**, so a
  transaction has several effectful steps, not one commit point.

### Lifecycle (supersedes the single-commit sketch)

```text
quote (no effect, firm+expiry) → accept quote (locks rate)
  → authorise payment (hold) → make payment
  → settlement phases, each with a regional cut-off (e.g. 18:00 UK local for UK payouts)
```

- `quote` is hedgeable. `accept`, `authorise`, `pay` and `settle` are
  effectful: each has its own idempotency key, own in-doubt handling and a
  defined compensation (cancel/unwind, possibly at a cost). Compensation is
  therefore real again after the first effectful step.
- Each step carries a **deadline**: quote expiry for accept; regional local-time
  cut-off for settlement. Deadlines drive scheduling priority. Local-time
  cut-offs need a timezone database (e.g. `tzdata`); this is the spike's first
  external dependency.

### Cascading objectives (policy as data)

Resolution order, most specific wins, others inherited:
`global default` → `merchant policy` (optional, paid) → `special customer policy`.

A merchant may also hold a **standing guarantee** (e.g. weekly guaranteed
rate). Per transaction we may route better than the guarantee; the difference
is our margin or exposure and must be accounted for per guarantee period
(ledger concern, deferred past the spike's core).

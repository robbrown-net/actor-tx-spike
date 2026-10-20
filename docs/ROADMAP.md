# Roadmap (single node first)

The narrative version of this plan, with risks and open questions, is in [ENGINEERING_STORY.md](ENGINEERING_STORY.md).

Gates: unit coverage 100%, integration coverage >85% (line coverage via `mix test --cover`).
Baseline on 2026-10-15: 76.5% total (Transaction 65.7%, ActorTxSpike 78.6%,
TransactionSupervisor 80%, Store 87.5%, Subsystem 94.4%, Application 100%).

See ADR 0001 and Amendment 1 for the domain model.

## Phase 0: Harness (independent of domain model) - DONE
- `test/unit` and `test/integration`; aliases `mix test.unit` / `mix test.integration`.
- Per-suite coverage thresholds (100 / 85) via `test_coverage`.
- Isolated tests: no cross-test leakage of global state.

## Phase 1: Provider model and fakes - DONE (2026-10-15)
- Provider adapter behaviour (quote / execute / status / probe).
- Controllable fake provider: latency, cost, error, stall, in-doubt, version drift.
- Provider state model + circuit breaker + rolling latency/error stats.

## Phase 2: Transaction as flow interpreter
Slices 1 and 2 DONE (2026-10-15); see below.
Slice 1 DONE (2026-10-15): `Policy` (validated scoring data), `Selector` (deadline-bound
fan-out quoting, constraints, scoring, baseline fallback), per-call timeouts in
`Provider.Server`. Slice 2 DONE: `Payment` lifecycle (accept/authorise/pay with per-step idempotency keys,
bounded retries, compensation via real provider undo operations, failover, in-doubt
reconciliation, `needs_review` escalation); `max_bps_from_best` policy constraint;
`Events` narrative stream; `Narrative`, `Panel` dashboard and `Demo` world; randomised
money-safety invariant test. Legacy spike modules removed.
Remaining: slice 3 cascading policy (global -> merchant -> customer, standing guarantees)
+ deadlines (regional settlement cut-offs, settlement phases).
Ideas parked: early stop when a "good enough" quote arrives; hedged request budgets.
- Declarative flow + scoring policy (data, validated into structs).
- Lifecycle per ADR Amendment 2: quote -> accept -> authorise -> pay -> settle, with
  per-step idempotency keys, deadlines (quote expiry, regional cut-off), in_doubt
  + async reconcile, and per-step compensation.
- Baseline outcome + best-so-far during quoting.
- Cascading policy resolution: global -> merchant -> customer; standing guarantees.

Slice 3 DONE (2026-10-15): `PolicySet` cascade, `Guarantee` + `Ledger`, `Cutoff`, deadline
enforcement and the `settle` step (ADR 0007). Remaining in this phase: multi-phase settlement,
timezone database behind the offset function.

## Phase 2b: Money, evidence and economics - DONE (2026-10-17)
- `Money`: integer minor units, scaled-integer rates, half-even rounding (ADR 0008).
- `Audit`: hash-chained, append-only, PAN-scrubbed log; `mix audit.verify`; `mix demo --audit PATH`.
- `Costs` + `Economics`: per-transaction cost model, price-discovery savings, guarantee margin,
  platform cost, per-provider totals; dashboard panel. See docs/compliance/COMPLIANCE_EVIDENCE.md.
Next on this thread: supervise the audit writer and fail closed; authentication and identity;
retention and off-host anchoring; reject card-like input; load/soak mode for realistic costs.

## Phase 2c: Replay the day - first version DONE (2026-10-17)
- `Journal` events in the audit log: request, policy, guarantee, settings, every provider
  answer with latency, operator actions. `Selector.evaluate/3` extracted as the pure decision.
- `Replay`: verified load, `state_at`, `Rules.verify` (11 rules), `WhatIf`, `Compare`; `mix replay`.
Next: re-execution engine (replay provider adapter, virtual clock) to rebuild a running world at
a moment; capture the policy cascade layers and the rate card; log compression and tiering;
branch-level what-if over several days; cost what-if.

## Phase 3: Sentinel and observability
Metrics sampler, rollups and Performance view DONE (see docs/architecture/07).
- Scheduled probes (configurable interval) + passive observation; drift detection.
- Event-sourced transaction log; `status/1`; telemetry (CPU, memory, storage, network proxies).
- Durable store behind a behaviour; atomic writes; retention/compaction.

## Phase 4: Prove it
- Property/chaos tests (stalls, drift, crashes mid-commit, restart) asserting no double payment.
- Load test for latency tails and resource budgets.

## Phase 3a: Durable recovery, jurisdiction and regional logs - DONE (2026-10-17)
- Write-ahead barrier and fail closed; group-commit fsync; one writer per log (ADR 0010).
- `Recovery`: open transactions resume from the log after a restart; demo resumes at start.
- `Eligibility`: jurisdiction rules before scoring; `Audit.Router` regional logs; `Residency` proof
  and `mix replay residency` (ADR 0011).
Supervised audit router and `require_audit` (production refuses to start without it) DONE 2026-10-20.
Next: retention and rotation; forward deal lifecycle on the log; sanctions and KYC gates; load mode
to measure barrier cost; alerting when the audit router restarts.

## Phase 3b: Design for Test lab - DONE (2026-10-20)
- Test lab tab (`mix demo --lab`): audit-log fault injection (down, fails after N steps), crash at an
  exact event, recovery plan (dry run) and recover, idempotency probes, router kill, log verification
  and residency, and a library of 17 scenarios with hard checks (docs/architecture/08-test-lab.md).
  The library runs as an integration test.

## Regulatory-driven items (added 2026-10-17; see docs/compliance/COMPLIANCE_EVIDENCE.md)
- Jurisdiction eligibility as a hard routing constraint (corridor, instrument, customer type).
- Sanctions and KYC gates before accept, recorded in the journal.
- Per-region audit logs and retention (data residency).
- Forward deal lifecycle (value date, confirmation, maturity settlement) on the durable store; trade reporting.
- Retail variant: disclosure, receipt, cancellation window, error resolution.

## Known gaps
- Transactions are not persisted (the audit log is evidence, not a recovery store): a node restart loses in-flight work. The old file store
  was removed with the legacy code; durable, event-sourced recovery is Phase 3.
- `Demo`, `Demo.Handler` are excluded from the unit gate (exercised by integration tests);
  `Mix.Tasks.Demo` is excluded everywhere (thin entry point).
- Finished transactions linger 30s (configurable) then stop; their story stays in the
  bounded event buffer only. A durable log is Phase 3.
- Unit coverage is line-based; branch-level measurement is still to do.

## Deferred
- Branch-level coverage measurement (requested; line coverage only today).
- Multi-node ownership, partition convergence, provider discovery.

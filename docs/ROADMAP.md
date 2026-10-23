# Roadmap (single node first)

The narrative version of this plan, with risks and open questions, is in [ENGINEERING_STORY.md](ENGINEERING_STORY.md).

Gates (all enforced in CI; see [DEVELOPER_GUIDE](DEVELOPER_GUIDE.md#3-quality-limits-and-how-they-are-enforced)):
unit coverage 100%, integration coverage >85% (line coverage via `mix test --cover`), cyclomatic
complexity at most 4, nesting at most 2, ABC size at most 40 and no duplicated block of mass 16
(`mix credo --strict`), no warnings, `mix format` clean. Position on 2026-10-22: unit 100%,
integration 87.2%, all code-shape limits met (ADR 0012), 441 non-system tests and 14 system tests.
Original baseline on 2026-10-15: 76.5% total.

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

## Phase 3c: Code-shape limits - DONE (2026-10-20)
- Cyclomatic complexity 4 (was 9), nesting 2, ABC size 40 and duplicate mass 16 enforced by Credo
  (ADR 0012). About 30 functions reshaped; new small modules `Selector.Narrator`,
  `Eligibility.Parser`, `Validation`, `Poll`; shared `Wait` test helper; `mix quality` alias.
- Documentation brought up to date: developer, testing, requirements and user guides.
Next: widen mutation testing to `Payment` and `Recovery`; make CI a required check; consider Dialyzer.

## Phase 3d: Bulk send - DONE (2026-10-21)
- Bulk send tab: 1 to 10,000 transactions, random amount between a minimum and maximum, a pause in ms,
  optional seed; "Randomise everything" draws merchant, customer, regions, instrument, customer type and
  currencies per transaction; progress counted from how each ends (docs/architecture/09-bulk-send.md).
- Source and destination currency carried on requests, shown in the story, recorded in the log.
- First load observation: 1,000 randomised transactions with no pause took about 12 s, with 29 ending in `needs_review`.
Next: use it for a load/soak measurement (barrier cost, bulkhead limits); cap in-flight transactions or
back off to a target rate; make currencies drive pricing and eligibility; record a batch's parameters in the log.

## Phase 3e: Load and back-pressure - DONE (2026-10-22)
- Traced the burst failures (R32) to three causes and fixed each (ADR 0013): admission control (a limit on transactions in flight,
  refusing the excess as *overloaded* before anything starts), priority classes in the provider bulkhead (quotes 60%, steps 100%,
  undo/status 150%), and waiting instead of failing (quotes ask a busy provider again until the deadline, steps ask again without using
  an attempt). The Bulk sender backs off, can cap itself, and reports why transactions did not complete.
- `mix load`: repeatable burst measurement per admission limit (docs/quality/LOAD_RESULTS.md). Result with the demo providers:
  30% of a 2,000 burst completes with no limit, 99.8% with a limit of 100; production default 100 (`ACTOR_TX_MAX_IN_FLIGHT`).
Next: measure with the audit log on (the write-ahead barrier's cost) and against real providers; a queue in front of the limit or
automatic tuning from observed latency; per-merchant fairness; record refusals; alert on sustained refusal; a long soak run.

## Phase 3f: Product depth - DONE (2026-10-23)
- **Currencies price, route and permit** (ADR 0014): a pair is priced from the market through each provider's spread, only providers that carry
  both currencies are asked, regions can restrict currencies (IN, CN in the demo).
- **Review workflow (UC-4)**: queue of `needs_review`, check, retry a safe step under its original key, resolve with who and why; write-ahead,
  in the transaction's own regional log, rebuilt from the logs, checked by the `review_recorded` replay rule; a Review tab.
- **Recorded batches**: each bulk batch's plan and result are in the audit log; `mix replay batches`.
Next: the re-execution replay engine (virtual clock, replay provider adapter); four-eyes approval, assignment and deadlines for reviews; minor-unit
exponents per currency and fee schedules; forward deal lifecycle; sanctions and KYC gates.

## Phase 4: Java and Spring port - IN PROGRESS (started 2026-10-23, ADR 0015)
- Chunk 1 DONE: quote and select over HTTP (exact money, policy cascade, jurisdiction rules, provider model, concurrent selector, sentinel, operator control, demo world as data); same bars as Elixir plus branch coverage and PIT mutation testing.
- Position on 2026-10-23: **673 unit tests (100% lines, 97.2% branches), 68 integration tests (86.7% lines), PIT 401/409**; all code-shape limits met. See [quality/QUALITY_SNAPSHOT](quality/QUALITY_SNAPSHOT.md) section 9 and [architecture/13](architecture/13-java-architecture.md).
- Chunk 2 DONE (2026-10-23): event bus, payment engine, audit log and router (write-ahead, fail closed), admission, economics, guarantees and cut-offs, SSE.
- Chunk 3 mostly DONE (2026-10-23): replay, review, bulk send and load runner, metrics, the existing dashboard served by Spring. Still to do: recovery after restart, CLI tools (replay, load, audit verify), dashboard actions `auto`, `pace`, `policy` and the test lab, Java load results in `docs/quality/LOAD_RESULTS.md`, PIT over the payment engine, a headless-browser smoke test of the dashboard, cross-verification of Java and Elixir audit logs, CI result check.
- Design change (ADR 0016, 2026-10-23): steps, workflows, provider capabilities and routing criteria are values, so new steps, per-corridor workflows, async settlement and new criteria need no engine change.
- Follow-ups from ADR 0016: review derives its step lists from the recorded workflow; replay keeps a fourth criterion's weight; record workflow name/version in `tx_received` before recovery is ported; webhook-confirmed steps.
- **Review the closed enums again** (`Outcome`, `Breaker`, `HealthState`, `Instrument`, `CustomerType`, `BulkheadClass`, review status, `Mode.Kind`) whenever the domain changes, and at the latest before the first new product or provider type: each is a bet that the set is closed. `Instrument` and `CustomerType` are the likeliest to be wrong (jurisdiction rules are data, they are not).
- Find-and-fix list for the Elixir side: `PolicySet.resolve/2` crashes with a `MatchError` when merchant and customer layers are each valid but invalid combined.

## Regulatory-driven items (added 2026-10-17; see docs/compliance/COMPLIANCE_EVIDENCE.md)
- Jurisdiction eligibility as a hard routing constraint (corridor, instrument, customer type).
- Sanctions and KYC gates before accept, recorded in the journal.
- Per-region audit logs and retention (data residency).
- Forward deal lifecycle (value date, confirmation, maturity settlement) on the durable store; trade reporting.
- Retail variant: disclosure, receipt, cancellation window, error resolution.

## Known gaps
- Recovery from the audit log exists (`Recovery`, ADR 0010) but is started by the host at boot
  (as `mix demo` does), not automatically by the application, because it needs the live provider registry. The demo world forgets provider state on restart.
- Retention is recorded in the jurisdiction rules but not enforced (no rotation, deletion or off-host copy).
- Not built: forward deal lifecycle, sanctions/KYC gates, trade reporting, retail flows, the review workflow (UC-4). The jurisdiction matrix is unverified by counsel.
- `Demo`, `Demo.Handler`, `Demo.Lab`, `Demo.Lab.Scenarios` are excluded from the unit gate (exercised by integration tests);
  the `Mix.Tasks.*` entry points and the `Wait` test helper are excluded everywhere.
- Finished transactions linger 30s (configurable) then stop; their story outlives them in the
  audit log (and in the bounded event buffer).
- Unit coverage is line-based; branch-level measurement is still to do.

## Deferred
- Branch-level coverage measurement (requested; line coverage only today).
- Multi-node ownership, partition convergence, provider discovery.

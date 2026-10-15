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

## Phase 3: Sentinel and observability
- Scheduled probes (configurable interval) + passive observation; drift detection.
- Event-sourced transaction log; `status/1`; telemetry (CPU, memory, storage, network proxies).
- Durable store behind a behaviour; atomic writes; retention/compaction.

## Phase 4: Prove it
- Property/chaos tests (stalls, drift, crashes mid-commit, restart) asserting no double payment.
- Load test for latency tails and resource budgets.

## Known gaps
- Transactions are not persisted: a node restart loses in-flight work. The old file store
  was removed with the legacy code; durable, event-sourced recovery is Phase 3.
- `Demo`, `Demo.Handler` are excluded from the unit gate (exercised by integration tests);
  `Mix.Tasks.Demo` is excluded everywhere (thin entry point).
- Finished transactions linger 30s (configurable) then stop; their story stays in the
  bounded event buffer only. A durable log is Phase 3.
- Unit coverage is line-based; branch-level measurement is still to do.

## Deferred
- Branch-level coverage measurement (requested; line coverage only today).
- Multi-node ownership, partition convergence, provider discovery.

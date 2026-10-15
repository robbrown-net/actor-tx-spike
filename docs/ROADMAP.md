# Roadmap (single node first)

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

## Known issues found along the way
- A persisted snapshot missing `:payload` crashes `restore_inflight/0` and therefore
  application boot (found when a unit test left a partial snapshot in the store file).
  Boot must tolerate malformed/corrupt snapshots (quarantine and report them).
- Legacy modules are excluded from the unit gate only (`@legacy` in mix.exs); remove
  entries as Phase 2 replaces them. Integration gate currently ~77% (needs >85%) because
  of legacy code.

## Deferred
- Branch-level coverage measurement (requested; line coverage only today).
- Multi-node ownership, partition convergence, provider discovery.

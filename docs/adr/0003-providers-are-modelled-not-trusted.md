# ADR 0003: Providers are modelled, not trusted

Status: accepted · Date: 2026-10-15

## Context
Providers are third parties that decay, stall, are delayed or change behaviour without
notice. The system must keep serving ("keep going") and protect money.

## Decision
* Each provider sits behind a `Provider.Server` with a **circuit breaker**, a rolling
  health window, a **bulkhead** (max in-flight) and **enforced per-call timeouts**.
* Health is a **pure model** (`Provider.Health`): `unknown / healthy / degraded / stalled / quarantined`.
* **Version drift quarantines** a provider until released; behaviour change on a money path is not trusted.
* **Recovery is gradual**: after a successful half-open probe, history is cleared and
  the provider stays `degraded` until `recovery_successes` consecutive successes.
* Selection never calls providers whose state the policy excludes.
* A **sentinel** probes on an interval; in the spike it is a demo timer.

## Alternatives considered
* *Trust provider responses*: unsafe.
* *Reset history on recovery then declare healthy*: a single lucky probe re-admits full traffic (rejected by the owner in favour of gradual).
* *Let old errors age out only*: a quiet provider never recovers.

## Consequences
* The model is deterministic and exhaustively testable (time is injected).
* Quarantine needs an operator or sentinel policy to release; review tooling is future work.

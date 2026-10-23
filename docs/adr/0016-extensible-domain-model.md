# ADR 0016: Steps, workflows and criteria are values, not enum members

Status: accepted (2026-10-23). Applies to the Java port ([ADR 0015](0015-java-spring-port.md)).

## Context

The first Java conversion of the payment engine used an `Operation` enum as a behaviour driver: `Payment` held four parallel maps keyed by it (undo, key name, story verb, phase) and three conditionals that encoded business rules ("after pay, never fail over", "a failed settle goes to a person"). Review and replay repeated the same step lists. Adding one step (sanctions screening, an FX hedge, pre-funding) meant touching the enum, the engine, the provider adapter, the fake, the presets, review and replay: shotgun surgery. Enums are an anti-pattern where a set is expected to grow and each member carries behaviour.

The design has to absorb four kinds of change without editing the engine: **more steps**, **different workflows per corridor or provider**, **steps that complete asynchronously**, and **new routing criteria**.

## Decision

1. **`Step` is an object** (`payment.Step`): it names its operation, idempotency-key part, story verb, phase and reason label, and declares its compensation (or none) and whether it is confirmed by status. The engine knows none of these. Its safety rules are derived from what steps declare: a step with no compensation is a point of no return (never fail over past it, later failure goes to a person); a step confirmed by status completes by asking the provider (reusing the in-doubt machinery).
2. **A payment runs a `Workflow`** (an ordered list of steps) chosen by a `Workflows` function per request and provider. The standard workflow is accept, authorise, pay, settle.
3. **`Operation` and `Phase` are values** (records with constants), not enums. A new step brings its own operation and phase.
4. **Providers declare capabilities.** `ProviderAdapter.perform(op, quote, key)` is the generic entry point (the typed methods remain for the standard operations) and `supports(op)`; a candidate that cannot do every step of its workflow is passed over (`unsupported_step`).
5. **Routing criteria are pluggable** (`policy.criteria.Criterion`, a `Criteria` registry): a new criterion is one class and one registry line; policy weights are keyed by criterion name; the JSON shape for the existing three is unchanged.
6. **One terminal vocabulary**: `payment.Outcome` (completed, failed, needs_review) replaces four duplicates; every `Result` has `outcome()`. An unfinished transaction is the absence of an outcome.
7. **The audit vocabulary is explicit**: event kinds are named once (`events.EventKinds`) and `tx_received` data carries a `schema` version. The record envelope and hash format are unchanged, so Elixir logs and tools keep working.

## Consequences

* Adding a step, a per-corridor workflow, an asynchronously confirmed step or a criterion is covered by tests that change none of the engine (`WorkflowTest`, `SelectorCriteriaTest`).
* Closed sets that really are closed stay enums: `Outcome`, `Breaker`, `HealthState`, `Instrument`, `CustomerType`, `BulkheadClass` and the review status. **Review them again when the domain changes** (see the roadmap).
* Known follow-ups: the review service still lists the standard steps and retryable undos itself (it should derive them from the transaction's recorded workflow); replay rebuilds only the three standard weights from a recorded policy (a fourth criterion would be lost on replay); async completion is by status polling only (webhooks would need an inbound path); `Instrument` and `CustomerType` are enums although jurisdiction rules are data.
* The workflow is not yet recorded per transaction in the audit log; recovery after restart (not yet ported) must resume at a step, so it should record the workflow name and version in `tx_received`.

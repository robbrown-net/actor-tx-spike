# ADR 0007: Cascading policy, standing guarantees, deadlines and settlement

Status: accepted · Date: 2026-10-15

## Context
The owner's objectives cascade (global at start-up, per merchant as a paid privilege,
per special customer). Merchants may hold a weekly guaranteed rate, while we may route
better or worse when transactions actually occur. Payments must be made as soon as
possible and no later than a regional local-time cut-off (e.g. 18:00 UK), followed by
settlement phases.

## Decision
1. **`PolicySet`** resolves global → merchant → customer. Overrides are *partial* (unspecified
   fields inherited; `weights` merge key by key then renormalise). A merchant layer applies only
   when `overrides_allowed` is true; otherwise it is ignored and the resolution says so. Every layer
   is validated when the set is built. The layers used are narrated on every transaction.
2. **Standing guarantees** are evaluated at completion: `margin = achieved_net − amount × guaranteed_rate`.
   Positive margin and negative exposure are recorded idempotently per transaction in a `Ledger`, and
   exposure is narrated loudly. Routing is not constrained by the guarantee (the scoring already
   maximises net value); a guarantee-aware constraint is future work.
3. **Cut-offs** (`Cutoff`) are local `hour:minute` plus a UTC offset that is a fixed integer **or a
   function of the date**, so DST rules can be supplied from a timezone database **without this
   project taking a runtime dependency on one**. Absolute instants (`Cutoff.fixed/2`) exist for tests and the demo.
4. **Deadline enforcement:** checked before every effectful step and failover.
   * before payment: stop, undo what was done, `failed {:deadline_missed, region}`;
   * after payment, before settlement: `needs_review {:settlement_overdue, region}`;
   * a payment already in doubt keeps reconciling past the cut-off (money may have moved).
5. **Settlement** is a final effectful step with **no undo**. Refusal or exhausted retries escalate to
   `needs_review {:settlement_failed, ...}`; the payment is never refunded automatically. Answer lost →
   reconcile like `pay`; unresolved → `needs_review {:in_doubt, :settle}`.

## Alternatives considered
* *Depend on `tzdata`:* correct but adds the first runtime dependency; deferred behind the offset function.
* *Make guarantees a routing hard constraint:* would reduce availability for a price we may choose to absorb.
* *Auto-refund on settlement failure:* a refund is itself an effect with cost and risk; a human decides.

## Consequences
* Regional rules are reference data the caller must supply correctly (risk R14).
* Margin accounting is in memory until the durable store exists.
* More terminal states for a human to resolve (UC-4 becomes more important).

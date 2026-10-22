# ADR 0014: Product depth: currencies drive pricing and eligibility, a review workflow, recorded batches

Status: accepted · Date: 2026-10-23

## Context
Three gaps stood between the engine and something a business could operate:

1. **Currencies were decoration.** A request could name a source and destination currency but nothing used it: every provider
   quoted one rate, every provider was asked, and no region could say which currencies it permits.
2. **`needs_review` was a dead end.** The system rightly stopped when it could not be sure what happened to money, but nobody
   could do anything about it: no queue, no way to ask the provider, no way to repeat a safe step, and no record of a person's
   decision (use case UC-4 was unbuilt).
3. **Bulk batches left no trace** in the audit log beyond the transactions they made, so a replayed day could not show what had
   been sent on purpose, and why.

## Decision

### Currencies
* A request's `source_currency` and `destination_currency` price, route and permit it.
* **Pricing.** A provider quotes a pair at the market (mid) rate carried through its own spread (`Provider.Fake`: `mid * rate / ref_rate`),
  so repricing, drift and healing move every pair. Requests without currencies, and unknown pairs, are quoted as before.
* **Capability.** A provider lists the currencies it carries. The selector does not ask a provider that lacks either currency
  (and says so in the story and in `excluded`); with no capable provider there is no quote.
* **Eligibility.** A region's rule may list `currencies`; both of a transaction's currencies must be allowed in every region of its
  corridor (the strictest wins), and the reasons name the currency and the region.
* Not done: currencies with different minor-unit exponents (JPY, KRW have none; the demo treats all as two decimals), cross-currency
  fee schedules, and holding or converting balances.

### Review workflow
* A transaction that ends in `needs_review` waits in a **queue** (`ActorTxSpike.Review`), open until a person closes it.
* A person can **check** with the provider (read-only status of each step's key), **retry** a step that is safe to repeat under its
  original key (settle and the undos), and **resolve** with an outcome (`paid`, `not_paid`, `written_off`), saying who and why.
* **Write-ahead.** Every action is emitted and flushed to the audit log *before* it takes effect; if the log cannot take it, nothing is
  sent and nothing changes. A decision without a name or a reason is refused.
* **Same logs.** A transaction's review actions are recorded in the regional logs the transaction was routed to: the audit router keeps
  routing a transaction that went to review until its resolution is recorded, and rebuilds that after a restart.
* **Rebuildable.** The queue is pure data (`Review.Queue`) built from live events or from audit-log records, so `Review.load/1` restores
  it after a restart and `mix replay reviews` reads it from a log alone.
* **Checkable in hindsight.** A replay rule (`review_recorded`) fails a resolution that lacks an outcome, a name or a reason, or that is
  recorded for a transaction that never went to review.
* Not done: four-eyes approval, assignment and queues per team, deadlines on open reviews, economics adjustments for a manual resolution,
  and retrying steps that need a quote (accept, authorise, pay).

### Recorded batches
* Starting and ending a Bulk send batch are recorded as `bulk_started` (the whole plan, in minor units, with seed and settings) and
  `bulk_finished` (how it ended and the counts). `Replay.batches/1` and `mix replay batches` read them back; `state_at` includes the
  batches so far.

### Deferred: re-execution replay
Re-running the orchestrator against recorded provider answers on a virtual clock (to rebuild a running world at a moment, and to test a
changed rule on behaviour rather than on quotes alone) is a project of its own and is not part of this change.

## Consequences
* Corridors behave like corridors: a dollar-to-rupee payment is priced near 83, only providers that carry rupees are asked, and India
  and China accept only the currencies their rules list.
* Random batches now include currency-refused combinations as well as instrument and customer-type ones.
* People can finish what the system refused to guess, and the evidence of who decided what and why is in the same tamper-evident log.
* Review depends on the audit log being up for every action (fail closed), consistent with the payment path.
* Provider capability and a region's currency list are separate ideas that can disagree (a region may allow a currency no eligible
  provider carries); that case ends in "no provider could give a usable quote", by design.

## Alternatives considered
* **Let a reviewer edit the transaction's state directly.** Simple, but it would bypass the write-ahead log and the provider's idempotency.
* **A reviewer UI that only reads.** Does not resolve anything; the point is a recorded decision.
* **Reject unsupported pairs at the provider** (ask and get "no"). Wastes a call and a bulkhead slot per provider per transaction.

# ADR 0013: Back-pressure: admission control, priority bulkheads and waiting instead of failing

Status: accepted · Date: 2026-10-22

## Context
The first burst test (1,000 randomised transactions, no pause) showed the system degrading by failing customers:
most ended with "no provider could give a usable quote", a few with `overloaded` accepts, and 29 in `needs_review`
because undo and settle calls were refused. Nothing limited how many transactions ran at once, each transaction fans out
to every provider, and each provider's bulkhead holds 10 calls, so a burst saturated every provider at once. Undo and
settle calls, which protect money already moved, competed for slots with brand-new quote requests.

## Decision
Back-pressure in four layers, each safe because an *overloaded* answer means the call was never made:

1. **Admission control** (`ActorTxSpike.Admission`). At most `max_in_flight` transactions run at once. A submission over
   the limit is refused immediately with `{:error, {:overloaded, retry_after_ms}}`, before any provider is asked or anything is
   recorded. A slot is held from admission until the payment ends or its process dies. A running id submitted again is the same
   transaction and does not count twice. Default: no limit; production sets 100 (`ACTOR_TX_MAX_IN_FLIGHT`). Changeable at run time.
2. **Priority classes in the provider bulkhead** (`Provider.Server`). Quote requests (new, sheddable work) may use 60% of the
   slots; accept, authorise, pay and settle (a transaction already under way) 100%; undo, status and probe 150%. Work already in
   motion and the calls that protect money are not starved by new work.
3. **Waiting instead of giving up.** A busy provider is asked for a quote again every 15 ms until the selection deadline.
   A busy provider on a step (accept, authorise, pay, settle) is asked again after the backoff without using up an attempt; the
   transaction's deadline bounds the wait (settlement past its cut-off still goes to review).
4. **A sender that backs off** (`Demo.Bulk`). A refused submission is held and retried after the suggested pause (counted as
   *throttled*); an optional sender cap makes the sender wait while that many of its own transactions are running.

## Consequences
* Under load the system completes what it admits, and tells callers to wait for the rest. Measured: with a limit of 100,
  1,000 of 1,000 and 1,995 of 2,000 complete; with no limit 30% of a 2,000 burst completes
  ([LOAD_RESULTS](../quality/LOAD_RESULTS.md)).
* The refusal is cheap and clean: no record, no provider call, nothing to undo. Callers must handle `{:overloaded, ms}`.
* Throughput is bounded by the limit; choosing it is an operational decision that needs measuring against real providers.
* More waiting means a transaction can take up to its deadline under load before it ends; quotes and steps hold their
  place instead of failing.
* Recovery-resumed transactions bypass admission (they were already admitted before the restart).
* Not done: a queue in front of the limit (refusal pushes the wait to the caller), automatic limit tuning, per-merchant
  fairness, and a replay record of refusals (they leave no trace in the audit log by design).

## Alternatives considered
* **Queue submissions inside the system.** Hides overload from callers, grows memory, and breaks the soft-real-time bound.
* **Do nothing but bigger bulkheads.** Moves the cliff, does not remove it; real providers set their own limits.
* **Fail fast on quotes (the previous behaviour).** Safe but fails customers who would have been served a few ms later.

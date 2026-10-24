# ADR 0017: Finished payments stop in batches; their results stay answerable

Status: accepted (2026-10-24). Elixir side only; the Java port keeps its own lifecycle until it is ported.

## Context

A `Payment` is one process per transaction. Until this decision a finished payment kept its process
alive for `linger_ms` (30 s by default, 120 s in the demo) and then stopped. Under a sustained
load the process count therefore grew with the number of payments in the window, not with the
number in flight: 3,000 payments in a batch left 3,000 extra processes for two minutes, and the
resident set grew with them (244 to 398 MB peak in the baseline runs).
`Performance Analysis/elixir-hot-path-and-audit-rerun.md` found this.

Some code reads a finished transaction after it has finished: `await/2` and `snapshot/1`
(the review queue reads the story of a `needs_review` transaction), the Test lab's "send it again
with the same key", and a repeated submission of the same id, which must not run the payment a
second time. Those reads are why the process was kept.

## Decision

1. **A finished payment does not keep its process.** On the way out it writes its final view
   (phase, result, history, provider, steps) to a store and hands its process to the reaper.
2. **`ActorTxSpike.Reaper` stops the processes in batches.** It is a GenServer that owns a public
   ETS table (the store). Every 500 ms (`:sweep_ms`) it stops every process that finished since
   the previous sweep, with one pass over the transaction supervisor.
3. **The store is bounded.** It keeps the last 10,000 finished views (`:keep`), oldest first. The
   reaper evicts the oldest id when the store is full.
4. **Lookups fall back to the store.** `await/2` and `snapshot/1` answer from the store when the
   process is gone. `ActorTxSpike.submit/2` returns `{:ok, id}` for a finished id that the store
   still holds, and does not start it again.
5. **The linger timer is retired.** The `linger_ms` option has no meaning any more and is removed.

## Alternatives considered

* **Stop each process immediately (`{:stop, :normal}`) after finishing.** Simplest, but the
  process could no longer answer, so the store is needed anyway, and each stop would cost a
  supervisor call on the hot path. Batching makes the stop cost one sweep per 500 ms rather than
  one per payment, and the store does not depend on the process being alive.
* **Keep the linger, but shorter.** Each finished payment would still hold a timer and a process
  for the linger period, so the process count would still be proportional to throughput times
  linger. A sweep bounds it by the sweep interval instead.
* **Use the event history as the store.** The event ring holds 1,000 events, about a dozen payments.
  Too small, and it has no per-transaction index.

## Consequences

* **The process count is flat.** In the measured runs (see Measurements) it returned to its idle
  value within one sweep of the batch's end, and stayed there.
* **Memory is bounded by the store.** Up to 10,000 finished views stay in ETS, a few megabytes
  of term data (see Measurements), instead of 10,000 processes.
* **Idempotency is bounded.** A finished id is the same transaction only while the store remembers
  it: the last 10,000 finished transactions. An older id submitted again would run again. The
  durable record of a transaction is the audit log, and recovery reads that, not the store. The
  store is not a durable idempotency record, and the Elixir spike has no durable idempotency
  store of its own; a production system needs one (see ROADMAP).
* **A reaper crash loses the store.** The table belongs to the reaper, so a crash empties it, and
  payments that had finished but not yet been swept stay alive until the node restarts. The
  reaper does no external calls and is small, so this is accepted rather than designed around.
* **Shorter lifetime for the lab.** `Lab.wait_gone/1` and the recovery tests wait for the process
  to disappear (the Registry no longer lists it) rather than for `snapshot/1` to return `nil`, since
  a finished snapshot is now answered from the store.
* **Public API unchanged** for callers: `await/2` and `snapshot/1` return the same shapes, and a
  finished transaction returns the same result as before. What changed is that `await/2` on an id
  that was never started, or whose view was evicted, returns `{:error, :not_found}` as before.

## Measurements

Demo server (`mix demo --pace 0.02 --port 4041 --quiet`), no audit log, 3,000 payments through the
bulk endpoint at an in-flight cap of 80, one machine (8 cores, load average 13 to 16 during the runs,
so CPU figures are noisy).

Five interleaved pairs, a fresh server for each run, same machine:

| Version | Payments/s (median, range) | CPU ms per payment (median, range) | Peak RSS | Processes after batch, +5 s, +60 s |
|---|---|---|---|---|
| Before (linger 120 s) | 97.0 (73 to 111) | 23.9 (13 to 28) | 265 to 283 MB | 3,231 to 3,436, then 3,136 at +5 s and +60 s |
| Reaper (this ADR) | 97.1 (78 to 104) | 26.6 (14 to 30) | 119 to 130 MB | 152 to 515, then 137 at +5 s and +60 s |

The process count is flat: it returns to the idle value of 137 within one sweep and stays there.
Throughput is the same. CPU per payment is about 11% higher at the median, but the ranges overlap
and the load average was 13 to 16 on 8 cores, so the difference is not established. The reaper's own
work measured about 15 microseconds per payment in-VM, roughly 0.1% of the total.

## Verification

* Unit tests for the reaper (`test/unit/reaper_test.exs`: store, batched stop, bound) and for
  payments (finished transactions stop and still answer; a repeated submission does not run the
  transaction again). Unit gate: 100% line coverage.
* Formatter and Credo strict clean.
* Lab scenarios and integration tests unchanged in intent; they pass with the process gone.

## Related

* [ADR 0010](0010-write-ahead-log-and-crash-recovery.md): the audit log is the durable record; recovery reads it.
* [ADR 0013](0013-back-pressure.md): admission counts a transaction until it reaches an end state. That
  is unchanged; the slot is released when the payment finishes, not when the process stops.

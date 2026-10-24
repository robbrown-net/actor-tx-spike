# Java audit log: group commit (java-0.2.0-groupcommit), 2026-10-24

Follows `elixir-hot-path-and-audit-rerun.md`, which found Java capped at about 10 payments/s with the audit log on. This note measures the forces (fsyncs) behind that cap, changes the barrier to group commit, and measures again. Raw numbers: `results.json` (rows with version `java-0.2.0-groupcommit`) and the benchmark output in the session scratchpad.

Method: `./java/run --actortx.audit.dir=<fresh dir> --actortx.audit.logs=IN,CN --actortx.demo.pace=0.02` (audit on, regions IN and CN plus GLOBAL). Bulk endpoint, 3,000 payments per in-flight cap (20, 80, 200), each cap after a 300-payment warm-up on the same server. Same script for both runs (`bulkbench3.py` logic, Java only, with counters read from `/api/metrics` `audit`). One machine (8 cores), single run per cell.

## 1. Result

| In-flight cap | Before tps | After tps | Before syncs/payment | After syncs/payment | Before records/sync | After records/sync |
|---|---|---|---|---|---|---|
| 20 | 10.4 | 16.7 | 4.10 | 2.49 | 15.7 | 25.8 |
| 80 | 10.4 | 18.4 | 4.11 | 2.31 | 15.6 | 27.8 |
| 200 | 10.2 | 20.2 | 4.12 | 2.08 | 15.6 | 30.8 |

Group commit roughly halves the forces per payment and about doubles the records each force covers. Throughput improves by 1.6x to 2.0x. It does not reach the 10x-plus that removing the cap would suggest, and the reason is in section 3.

## 2. Syncs and the barrier before and after

Counted on the audit router (sum over the regional logs; each log's force counts once).

| | Before (dispatcher forces) | After (group commit) |
|---|---|---|
| Who forces the disk | the event-bus dispatcher thread, inside each barrier | one `audit-sync` thread per log; the dispatcher only starts the flush |
| When a barrier returns | after its own force (or after a no-op if nothing dirty) | after a successful force that started after its records were written |
| Forces per payment | about 4.1 (each barrier forced on its own) | about 2.1 to 2.5 (concurrent barriers share the next force) |
| Records per force | about 15.6 | about 26 to 31 |
| Force failure | sticky `audit_unavailable`; later barriers see the same reason | same; every barrier waiting on the failed force gets the same reason |

The force rate is almost the same before and after: 42.6/s before (12,304 forces in 288.7 s), 41.6/s, 42.5/s and 42.1/s after. Throughput is that rate divided by syncs per payment (42.6 / 4.1 = 10.4; 42 / 2.3 = 18).

## 3. Why throughput stops at about 20/s

The disk completes about 42 forces per second in both runs, which is about 24 ms per force if the disk is kept busy. Each run is therefore limited by the rate at which forces complete, not by the dispatcher. Group commit cut the forces needed per payment, but not the rate at which the disk completes them.

This is an inference from the numbers and the earlier single-thread timing (`FileChannel.force` p50 22 ms). I did not measure the device's serialisation directly. The next lever is fewer barriers per payment, not more batching: each step's barrier has to wait for its records, so a payment with N steps needs at least one force wave per step. Changing that is a design decision about where barriers sit and is out of scope here. The guarantee is unchanged.

## 4. What the guarantee looks like now

- A barrier's future completes only after a successful `force()` that started after its records were written (`AuditLog.forceAndRelease`). Tested: a barrier stays pending while the force is blocked, and the file already holds the records when the force starts.
- A failed force fails every barrier waiting on it, with the same reason as before, and the log refuses writes from then on. Tested with two waiters, one of them queued during the blocked force.
- Concurrent appenders share forces: 8 threads, 200 records, syncs strictly fewer than records, and the chain verifies intact afterwards. Tested with a 2 ms forcer.
- Records keep their order (the dispatcher writes them in order; the sync thread only forces).
- Regional routing, the one-writer lock and the hash chain are unchanged.
- With `sync` off (tests, throwaway runs) no thread is started and barriers complete at once, as before.

## 5. Verification

`./java/jmvn verify`: BUILD SUCCESS. Unit tests 692 (100% line coverage gate met), integration tests 68 (gate met), Checkstyle 0 violations, PMD and CPD clean, Spotless applied.

## 6. F_FULLFSYNC: what macOS gives for `FileChannel.force`

Note only; no semantic change was made.

- On macOS, `fsync(2)` asks the kernel to write the data to the drive, but the drive may still hold it in its volatile cache. `F_FULLFSYNC` (an `fcntl`) also asks the drive to flush that cache. The stronger guarantee costs more time.
- `FileChannel.force(false)` maps to the JDK's native force. I did not verify from the JDK source in this environment which system call it issues on macOS. Recall suggests a plain `fsync`, possibly `F_FULLFSYNC`, and I did not confirm either.
- The timing points to something heavier than the Erlang `:file.datasync` on the same disk (22 ms against 0.29 ms). That gap is consistent with Java issuing a full drive flush and Erlang issuing a plain sync, but it is not proof.
- To settle it: check the JDK's `FileDispatcherImpl` native source for the JDK in `.tooling`, or trace the syscall (needs root; `dtruss` is restricted under SIP).
- Whichever it is, the guarantee in ADR 0018 is stated as "forced to the disk", and the choice of call should be recorded there once known.

## 7. Caveats

- One machine, one run per cell, after a 300-payment warm-up per server. Figures can move by 10% or more between runs.
- The records/sync figure is summed over regional logs and GLOBAL. A force on one log does not cover another, so per-log numbers are lower.
- `syncs` counts force attempts, including failed ones; the warm-up runs are excluded by taking counter deltas.
- CPU is OS CPU time of the server process and includes JVM work; it does not isolate the disk.
- The shutdown log line (`audit closed: ...`) was not seen in the server log on a `kill` stop. The figures are available live on `/api/metrics` (`audit`), which is the test hook used here. Shutdown logging is unverified.
- The earlier Elixir and Java numbers came from a different session; this run did not rerun them.

## 8. Next

1. Reduce forces per payment at the design level (where barriers sit), and measure again.
2. Confirm which call the JDK makes on macOS (section 6) and record it in ADR 0018.
3. Rerun on Linux, where `fsync` costs differ.

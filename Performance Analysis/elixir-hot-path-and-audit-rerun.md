# Elixir hot path, and the like-for-like rerun with the audit log on (2026-10-24)
> **Update 2026-10-25:** sections 6 and 8 are superseded. Java's audit cap was measured and reduced by group commit (`java-group-commit.md`); Elixir payments now stop when finished (`elixir-process-reaping.md`). Start at `README.md`.

Follows `java-vs-elixir-throughput.md`. Raw numbers: `results.json` in this folder.

Method: pace 0.02, 3,000 payments per case through the bulk endpoint, in-flight caps 20, 80 and 200.
Elixir: `mix demo --pace 0.02 --port 4041 --quiet [--audit-dir DIR]`. Java: `./java/run --actortx.demo.pace=0.02 [--actortx.audit.dir=DIR --actortx.audit.logs=IN,CN]`.
Both audit runs wrote three logs (Elixir GLOBAL, IN, CN; Java GLOBAL always, plus IN, CN).
CPU is OS CPU time of the server process (`ps -o time`) over the run, divided by completed payments.
One machine, 8 cores, single runs unless stated.

## 1. Headline

- **With the audit log on, the Java server drops to about 10 payments/s at every in-flight cap. Elixir stays at 49 to 61/s.** Without the audit log, Java was 217 to 307/s. The earlier finding that Java is about 5x cheaper per payment on CPU holds only without the audit log.
- The Java audit slowdown is consistent with a forced disk sync on macOS: `FileChannel.force(false)` costs about 22 ms here, against 0.3 ms for the Erlang `:file.datasync` that Elixir uses. Java runs its barriers through one dispatcher thread, so the cap does not help. Not yet proven by instrumenting the barrier path (section 5).
- In Elixir, the largest single share of CPU per payment is the BEAM scheduler busy-wait, not payment work. Turning it off cut CPU per payment from about 26 ms to about 9.5 ms, but cut throughput by about 15 to 20 percent.

## 2. Like-for-like rerun, audit log on (3,000 payments, pace 0.02)

| In-flight cap | Server | Completed/s | CPU ms/payment | Peak RSS | Threads |
|---|---|---|---|---|---|
| 20 | Java | 10.3 | 20.5 | 500 MB | 56 |
| 20 | Elixir | 61.1 | 40.1 | 237 MB | 32 |
| 80 | Java | 9.5 | 14.9 | 504 MB | 55 |
| 80 | Elixir | 59.2 | 47.4 | 369 MB | 32 |
| 200 | Java | 10.3 | 15.7 | 515 MB | 57 |
| 200 | Elixir | 48.7 | 47.0 | 424 MB | 32 |

Audit log size: about 25 to 27 KB per payment for both runtimes (about 67 events per payment).

## 3. Audit off versus audit on

| Server | Audit | 20 in flight /s | 80 /s | 200 /s | CPU ms/payment at 80 |
|---|---|---|---|---|---|
| Java | off | 217 | 257 | 307 | 3.8 |
| Java | on | 10.3 | 9.5 | 10.3 | 14.9 |
| Elixir | off | 79 | 90 | 102 | 27.0 (earlier run); 25.7 and 27.3 in the profiling runs |
| Elixir | on | 61.1 | 59.2 | 48.7 | 47.4 |

Elixir loses 23 to 52 percent of its throughput with the audit log on (the loss grows with the cap) and its CPU per payment rises from about 27 to about 47 ms. Java loses 95 to 97 percent of its throughput.

Spot check, same session: Java with the audit log off, a cold 300-payment run at cap 80, gave 174/s.

## 4. Elixir hot path (no audit, cap 80, 3,000 payments)

### 4.1 Events, subscribers and mailboxes
- Events emitted per payment: **about 67** (200,360 events for 3,000 payments).
- Event subscribers: **4** (`Metrics`, `Economics`, `Review`, `Demo.Bulk`). So about 268 message deliveries per payment.
- Mailbox sampling every 0.5 s during runs: the `Events` mailbox and the largest mailbox of any process were **never above 1**.
- Payment processes are **not stopped** when a payment finishes. Process count rose by exactly 3,000 per 3,000 payments (3,144 to 6,144 to 9,145 over three runs). BEAM total memory rose from about 204 MB to 325 MB over two runs. Not proven to be a leak: it may be deliberate retention for inspection. Worth a look.

### 4.2 Where the reductions go (one run; per-module deltas over live processes)

Total: 149.1 million reductions for 3,000 payments, about 49.7k per payment. Reductions are a proxy for work, not time.

| Process (module) | Reductions (M) | Share | Note |
|---|---|---|---|
| `Provider.Server` (5 simulated providers) | 35.3 | 24% | the demo world's simulation, not payment logic |
| `Metrics` (event subscriber) | 19.0 | 13% | about 95 reductions per event |
| `Events` (the fan-out GenServer) | 16.5 | 11% | about 82 reductions per event |
| `Payment` (all payment processes) | 12.3 | 8% | about 4.1k per payment |
| `Economics` (subscriber) | 4.1 | 3% | |
| `Demo.Bulk` (subscriber) | 3.6 | 2% | |
| `Demo` | 3.0 | 2% | |
| `Review` (subscriber) | 2.8 | 2% | |
| `Provider.Fake` | 1.7 | 1% | |

The event pipeline (`Events` plus its four subscribers) is about **31 percent** of reductions.

### 4.3 Busy-wait test (cap 80, fresh server each run, OS CPU per payment)

| Flags | Run 1 | Run 2 | Run 3 | Completed/s (runs 1 / 2 / 3) |
|---|---|---|---|---|
| default | 25.7 | 27.3 | 12.2 (outlier, see caveats) | 119.9 / 110.3 / 100.8 |
| `+sbwt none +sbwtdcpu none +sbwtdio none` | 8.9 | 10.4 | 9.5 | 87.5 / 95.4 / 94.6 |

With busy-wait off, the BEAM's own `runtime` counter also fell from about 23 to about 7 ms per payment. So spinning is counted inside the runtime figure too, and CPU attributed to "process work" is overstated under the default flags.

## 5. Verdicts

| Hypothesis | Verdict | Evidence |
|---|---|---|
| Event fan-out per payment is a big cost | **Confirmed, a substantial share but not the whole story** | 67 events and 4 subscribers per payment; event pipeline about 31% of reductions. |
| GenServer serialisation (`Events` or a subscriber is a bottleneck) | **Refuted at these rates** | Mailboxes never above 1 at 0.5 s sampling. Short spikes between samples cannot be excluded. |
| BEAM scheduler busy-wait inflates "CPU per payment" | **Confirmed, and it is the largest single effect** | 26 ms to about 9.5 ms per payment with `+sbwt none`. Costs about 15 to 20 percent of throughput. |
| Java's audit slowdown is a durability fsync | **Strongly suggested, not proven** | Java `FileChannel.force(false)` p50 22.4 ms (p90 37 ms); Erlang `:file.datasync` p50 0.29 ms (same disk). Java's throughput is flat across caps, which fits a serialised sync. |

## 6. Java with audit: what I did not establish
- I did not instrument the Java barrier, so the number of forced syncs per payment is not counted. About 45 syncs per second at 22 ms each would give about 10 payments/s, which fits if each payment forces roughly four or five times.
- Two thread dumps during a run showed no application frames, probably because the bulk workers are virtual threads, which a platform-thread dump does not list. That is why I used the disk timing instead.

## 7. Caveats
- One machine, one run per cell except the busy-wait test (three runs each). Run 3 of the default-flags profiling set is an outlier (12.2 ms per payment against 26 to 27 in the other two). I could not explain it. It may be a measurement artefact, for example the previous server still shutting down during its start-up `ps` reading. The conclusion does not depend on it.
- CPU is OS CPU time. It includes BEAM busy-wait and JVM JIT and GC. Reductions are a proxy for work.
- Simulated providers (fixed latency, 10 concurrent calls each) are part of the CPU bill on both sides. In a real deployment the provider calls would be remote and the local CPU share smaller.
- Java and Elixir audit logs differ in one detail: the Java server writes IN and CN plus GLOBAL, as does the Elixir demo (GLOBAL, IN, CN). Both wrote about the same bytes per payment.
- The Java and Elixir servers were restarted for each runtime. Each runtime had one 300-payment warm-up before its three measured caps.
- The earlier no-audit numbers came from a different session. I did not rerun them.
- The first Java attempt in this session was invalid: a Java server I did not start was already on port 4040, so my warm-up and bulk calls reached it and all 3,000 payments in each case failed. Those rows were discarded and the Java runs repeated on a free port. The first Java attempt was also stopped by my script's cleanup, which I have since restricted to the process it starts.

## 8. Next
- Count forced syncs per payment in the Java barrier path, and test whether batching the barrier per payment rather than per money step lifts the ceiling. Java's fsync choice is a design decision: F_FULLFSYNC is the stronger guarantee on macOS. Either way it should be documented as the reason for the audit cost.
- If the Elixir payment processes are not meant to outlive their payment, stop them when they finish (or confirm they are kept on purpose and bound the history).
- Profile the Elixir hot path on Linux, where busy-wait and fsync costs differ.

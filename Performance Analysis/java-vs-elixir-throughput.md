# Java vs Elixir: throughput, memory, threads, CPU (2026-10-24)

Question: the dashboard showed about 7 tps (Java) vs 6 tps (Elixir), Java uses more memory and has more than 30 threads, Elixir looks lighter. Why?

## 1. The 7 vs 6 is the sender, not the server
Both bulk senders pause 100 ms between sends, which caps one sender near 10 tps. Same 100-payment batch:

| Server | tps at default pause |
|---|---|
| Java | 8.8 |
| Elixir | 9.3 |

Repeated Java runs ranged 5.6 to 10.7 tps, so a 1 tps gap is noise. At demo pace each simulated provider allows 10 concurrent calls with fixed latency, so the simulated provider is the bottleneck. Making providers 15x faster raised throughput about 30x.

## 2. Saturation (pace 0.02, 3,000 payments, 8 cores, one machine, one run each)

| In-flight cap | Server | Completed/s | CPU ms/payment | Peak memory | Threads |
|---|---|---|---|---|---|
| 20 | Java | 217 | 6.4 | 491 MB | 56 |
| 20 | Elixir | 79 | 22.5 | 224 MB | 32 |
| 80 | Java | 257 | 3.8 | 496 MB | 55 |
| 80 | Elixir | 90 | 27.0 | 354 MB | 32 |
| 200 | Java | 307 | 3.9 | 507 MB | 56 |
| 200 | Elixir | 102 | 20.3 | 483 MB | 32 |

Idle: Java 276 MB / 46 threads; Elixir 74 MB / 32 OS threads.

## 3. Reading
- Memory: JVM heap, code cache, metaspace; the BEAM is smaller at idle.
- Threads: the BEAM also has about 32 OS threads (schedulers, dirty schedulers). Java adds web server, carrier pool, GC and JIT threads.
- CPU: Elixir looks lighter when mostly waiting (demo state); at saturation Java used about 5x less CPU per payment.

## 4. Not established
- Why Elixir costs more CPU per payment. Hypotheses only: per-payment event fan-out to subscribers, GenServer serialisation, BEAM scheduler busy-wait counted as CPU.
- JIT warm-up (first Java run slower), single machine, single runs, simulated providers, Java without audit log or Auto traffic.

## 5. Next
Profile the Elixir hot path (not yet done); rerun both with the audit log on for a like-for-like comparison.

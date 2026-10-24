# Performance Analysis

Measurements and decisions on throughput, CPU, memory and threads for the Elixir and Java builds. All figures: one 8-core machine, simulated providers, bulk-send endpoint. Raw numbers for the dashboard's Version history charts are in `results.json`.

## Reading order

| File | Question it answers |
|---|---|
| `java-vs-elixir-throughput.md` | Why did the dashboard show 7 tps (Java) vs 6 tps (Elixir)? (The sender's 100 ms pause and the simulated providers, not the runtimes.) |
| `elixir-hot-path-and-audit-rerun.md` | Where does Elixir spend CPU, and what does the audit log cost both builds? |
| `java-group-commit.md` | Can Java's audit barrier be faster without weakening the guarantee? (Yes, 1.6x to 2.0x, ADR 0018.) |
| `elixir-process-reaping.md` | Should finished Elixir payments stop? (Yes, in batches, ADR 0017.) |

## Where things stand (completed payments per second, 3,000 payments, pace 0.02)

| Build | Audit log off | Audit log on |
|---|---|---|
| Java 0.1.0 | 217 to 307 | about 10 |
| Java 0.2.0 (group commit) | not re-measured | 16.7 to 20.2 |
| Elixir 0.1.0 | 79 to 102 | 49 to 61 |
| Elixir 0.2.0 (reaper) | about 97 (cap 80) | not re-measured |

## Decisions
- The audit guarantee (a step waits for a disk sync covering its records) is kept. Its cost is accepted and documented: the disk's sync rate limits both builds.
- Java's remaining audit limit is about 2 waits per payment; going lower is a design change, not yet scoped.
- Which sync call the JDK makes on macOS (full flush or not) is unverified.
- Elixir busy-waiting is the largest CPU cost (about 26 to 9.5 ms per payment with it off, at 16% less throughput); left at the default.

## Adding a version to the charts
Append rows to `results.json` (`version`, `date`, `runtime`, `audit`, `inflight`, `tps`, `cpu_ms_per_tx`, `peak_rss_mb`, `threads`). The Performance tab's Version history reads this file.

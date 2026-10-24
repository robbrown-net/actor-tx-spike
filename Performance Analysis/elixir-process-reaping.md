# Elixir: stopping finished payments (elixir-0.2.0-reaper), 2026-10-24

Finding from `elixir-hot-path-and-audit-rerun.md`: payment processes were never stopped after finishing (3,000 payments left 3,000 processes alive until a 120 s linger). Decision (ADR 0017): stop them in batches, keep results answerable.

## Change
A reaper process stops all payments that finished since its last sweep, every 500 ms. A bounded store (last 10,000 finished payments) answers status and result requests after the process is gone, and re-submitting a finished id returns it without running it again.

## Result (demo, pace 0.02, 3,000 payments, cap 80, five interleaved pairs, fresh server each run, no audit log)

| Version | Payments/s (median) | CPU ms/payment (median, range) | Peak RSS | Processes after the batch, then at +5 s |
|---|---|---|---|---|
| Before | 97.0 | 23.9 (13 to 28) | 265 to 283 MB | 3,231 to 3,436, then 3,136 (still 3,136 at +60 s) |
| Reaper | 97.1 | 26.6 (14 to 30) | 119 to 130 MB | 152 to 515, then 137 (flat at +60 s) |

- Process count and memory: clearly better (memory roughly halved).
- Throughput: unchanged.
- CPU per payment: about 11% higher at the median, but the ranges overlap and the machine ran at load 13 to 16 on 8 cores. Not an established difference. The reaper's own work measured about 15 microseconds per payment.

## Caveats
Single machine, loaded. The store holds 10,000 finished payments, so re-submitting an older id would run it again. If the reaper crashes, the store is lost and unswept finished processes stay alive until restart.

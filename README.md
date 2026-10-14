# actor-tx-spike

Elixir OTP spike for a real-time actor system: **message transactions with explicit states**, plus **supervised subsystems** that also hold state.

Latency assumption: **soft real-time (milliseconds)**. That is why this spike is Elixir on the BEAM. If you later need sub-millisecond tails or heavy CPU work, the same shape ports to Rust.

## What it demonstrates

1. A transaction `gen_statem` with states `queued → awaiting_a → awaiting_b → committed`, and `compensating → failed` on exhausted retries after A has already run.
2. Two fake subsystems (`:subsystem_a`, `:subsystem_b`) under a `one_for_one` supervisor.
3. Crash a subsystem mid-flight; OTP restarts it and the transaction retries (at-least-once + idempotent `tx_id`).
4. Persist snapshots to disk; kill the transaction process; `restore_inflight/0` resumes work.

```text
Ingress → Transaction (gen_statem)
            ├─ Subsystem A (GenServer)
            ├─ Subsystem B (GenServer)
            └─ Store (disk-backed snapshots)
Supervisor restarts A/B independently of the transaction.
```

## Run

This repo vendors a local OTP 27 + Elixir 1.18 toolchain under `.tooling/` (gitignored) so you do not need Homebrew.

```sh
export PATH="$PWD/.tooling/otp/bin:$PWD/.tooling/elixir/bin:$PATH"
mix test
iex -S mix
```

In IEx:

```elixir
{:ok, id} = ActorTxSpike.submit(%{amount: 10})
ActorTxSpike.await(id)
ActorTxSpike.snapshot(id)

ActorTxSpike.Subsystem.crash_next(:subsystem_a)
{:ok, id} = ActorTxSpike.submit(%{amount: 11})
ActorTxSpike.await(id)
```

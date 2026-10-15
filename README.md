# actor-tx-spike

Elixir OTP spike for a real-time actor system: **stateful transactions** orchestrated across **unreliable third-party providers** (FX quote + payment), with health tracking, failover, compensation and a live monitoring dashboard.

Latency assumption: **soft real-time (milliseconds)**. That is why this spike is Elixir on the BEAM. If you later need sub-millisecond tails or heavy CPU work, the same shape ports to Rust. This is deliberate - when architecting in greenfield space, I follow I think Fred Brooks' advice: "Start with the highest level programming language that fits the requirement [because it is the most productive path], but ensure it has an escape route if you run into performance or other insurmountable problems." As a programming language of today that addresses complaints about popular programming languages of yesterday, Rust seems promising. Now to see what I can achieve in this POC!

## What it demonstrates

A transaction asks several providers for FX quotes, scores them under a cascading policy (global → merchant → customer), then accepts the best quote, authorises, pays and settles before a regional cut-off, failing over and compensating when providers misbehave, and accounting margin against any standing guarantee. See [docs/adr/0001-orchestrated-transactions.md](docs/adr/0001-orchestrated-transactions.md) for the reasoning and [docs/ROADMAP.md](docs/ROADMAP.md) for status.

```text
submit ─► Payment (one process per transaction, owns the lifecycle)
            │  quote ─► Selector ─► Provider.Server ×N ─► adapter (Fake in the demo)
            │             │ policy: weights, constraints, max bps from best
            │             └ ranked candidates, best-so-far, baseline fallback
            ├─ accept ► authorise ► pay        (each with its own idempotency key)
            ├─ definite failure ─► undo what happened, fail over to next candidate
            ├─ doubt (timeout / answer lost) ─► ask the provider (status) ─► never guess
            └─ unresolvable payment ─► needs_review (never fail over: no double pay)

Provider.Server = circuit breaker + health model + bulkhead + enforced call timeouts
Events          = the narrative: every component announces what it is doing
Panel           = live dashboard over that narrative (and a control surface)
```

## Run

This repo expects OTP 27 + Elixir 1.18. A local toolchain can live in `.tooling/` (gitignored, not part of the clone); no Homebrew is needed.

```sh
export PATH="$PWD/.tooling/otp/bin:$PWD/.tooling/elixir/bin:$PATH"
mix test                 # everything
mix test.unit            # unit suite, fails below 100% line coverage
mix test.integration     # integration suite, fails below 85% line coverage
mix test.regression      # tests pinned to defects found so far
mix test.system          # boots the real system and drives it over HTTP/SSE
mix demo                 # live world + dashboard on http://127.0.0.1:4040
python3 scripts/mutate.py  # mutation testing of the pure core (slow, on demand)
```

### Watch it work

`mix demo` starts five fake providers with different prices and speeds and serves a dashboard (loopback only). Submit transactions, switch on auto traffic, then break providers with the buttons (slow, stall, error, authorise fails, pay answer lost, pay vanishes, version drift) and watch selection, failover, compensation and reconciliation unfold in the **Story** pane. Click a transaction to follow just its story. The same narrative is printed in the terminal unless `--quiet` is given.

### From IEx

```elixir
{:ok, fake} = ActorTxSpike.Provider.Fake.start_link(id: :alpha, rate: 1.08)
{:ok, server} = ActorTxSpike.Provider.Server.start_link(adapter: ActorTxSpike.Provider.Fake, config: fake, id: :alpha)
{:ok, policy} = ActorTxSpike.Policy.new(%{"weights" => %{"cost" => 1}})

ActorTxSpike.Events.subscribe()
{:ok, id} = ActorTxSpike.submit(%{amount: 1000}, providers: [%{id: :alpha, server: server}], policy: policy)
ActorTxSpike.await(id)
flush()   # the story, as {:tx_event, %ActorTxSpike.Events.Event{}} messages
```

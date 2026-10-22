# actor-tx-spike

Elixir OTP spike for a real-time actor system: **stateful transactions** orchestrated across **unreliable third-party providers** (FX quote + payment), with health tracking, failover, compensation and a live monitoring dashboard.

Latency assumption: **soft real-time (milliseconds)**. That is why this spike is Elixir on the BEAM. If you later need sub-millisecond tails or heavy CPU work, the same shape ports to Rust. This is deliberate - when architecting in greenfield space, I follow I think Fred Brooks' advice: "Start with the highest level programming language that fits the requirement [because it is the most productive path], but ensure it has an escape route if you run into performance or other insurmountable problems." As a programming language of today that addresses complaints about popular programming languages of yesterday, Rust seems promising. Now to see what I can achieve in this POC!

> **Java and Spring port (in progress):** [`java/`](java/README.md) holds a Java 21 / Spring Boot 3 conversion, built in end-to-end chunks and held to the same quality bars ([ADR 0015](docs/adr/0015-java-spring-port.md)). Chunk 1, quote and select over HTTP, is done: `cd java && ./jmvn verify`.

## What it demonstrates

A transaction asks several providers for FX quotes, scores them under a cascading policy (global → merchant → customer), then accepts the best quote, authorises, pays and settles before a regional cut-off, failing over and compensating when providers misbehave, and accounting margin against any standing guarantee. Around that core it also:

- keeps money **exact** (integer minor units, scaled-integer rates);
- writes a **tamper-evident, write-ahead audit log** (hash-chained, one log per region for data residency) and **fails closed**: if the log is unavailable, no money moves;
- **recovers** open transactions from that log after a restart, never paying twice;
- applies **jurisdiction rules** (eligible providers, permitted instruments and customer types, where records may live) before scoring;
- can **replay a day** from the log: the state at any moment, rule verification, what-if strategies, comparison with another system;
- prices what each transaction **costs and saves**;
- **prices and routes by currency pair** (market rate through each provider's spread, providers that carry both currencies, regional currency rules);
- **lets people resolve what it would not guess**: a Review queue to check, safely retry and resolve with who and why, in the audit log;
- **protects itself under load**: an admission limit refuses the excess as "overloaded" before anything starts, provider slots are reserved for undo and in-flight work, and busy providers are waited for (`mix load` measures it);
- **sends many transactions** at once (the Bulk send tab: 1 to 10,000, random amounts between a minimum and maximum, a pause in milliseconds, and an option to randomise every setting).

See [docs/adr/0001-orchestrated-transactions.md](docs/adr/0001-orchestrated-transactions.md) for the reasoning and [docs/ROADMAP.md](docs/ROADMAP.md) for status. Documentation by audience ([full index](docs/README.md)): [user guide](docs/USER_GUIDE.md), [developer guide](docs/DEVELOPER_GUIDE.md), [testing guide](docs/TESTING.md), [requirements](docs/REQUIREMENTS.md), [architecture](docs/architecture/05-system-structure.md).

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
mix demo --lab           # the same, plus the Test lab tab (see below)
python3 scripts/mutate.py  # mutation testing of the pure core (slow, on demand)
mix quality              # before committing: format check + Credo strict (code-shape limits) + unit gate
```

### Quality limits

Enforced in CI by `mix credo --strict` and the coverage gates (reasons in [ADR 0012](docs/adr/0012-code-shape-limits.md), how to stay inside them in the [developer guide](docs/DEVELOPER_GUIDE.md#3-quality-limits-and-how-they-are-enforced)):

| Limit | Value |
|---|---|
| Cyclomatic complexity per function | 4 or less |
| Nesting depth | 2 or less |
| ABC size per function (`lib/`) | 40 or less |
| Duplicated code (`lib/`) | none at mass 16 |
| Unit coverage / integration coverage | 100% / above 85% |
| Compiler warnings, formatting | none / `mix format` clean |

`mix demo` options:

| Option | What it does |
|---|---|
| `--port 4545` | dashboard port (default 4040) |
| `--auto` | start with automatic traffic |
| `--quiet` | do not print the narrative to the terminal |
| `--pace 0.2` | scale provider latencies (1.0 = human-watchable; smaller is faster) |
| `--audit audit/day.log` | keep one tamper-evident audit log; open transactions in it are resumed at start |
| `--audit-dir audit/` | one log per region (GLOBAL, IN, CN): data residency; resumes open transactions |
| `--lab` | the **Test lab** tab: regional logs in a fresh temp directory, under a supervisor |

### Watch it work

`mix demo` starts five fake providers with different prices and speeds and serves a dashboard (loopback only). Submit transactions, switch on auto traffic, then break providers with the buttons (slow, stall, error, authorise fails, pay answer lost, pay vanishes, version drift) and watch selection, failover, compensation and reconciliation unfold in the **Story** pane. Click a transaction to follow just its story. The same narrative is printed in the terminal unless `--quiet` is given.

### Bulk send

The **Bulk send** tab sends a batch of transactions: how many (10, 100, 1,000, 10,000 or any 1 to 10,000), the smallest
and largest amount (each transaction gets a random amount in between), and the pause between transactions in
milliseconds. *Send the batch* uses the header's settings; *Randomise everything* draws merchant, customer, regions,
instrument, customer type and currencies afresh for every transaction (some combinations are refused on purpose, such
as a forward to IN). An optional seed repeats a random batch exactly. Progress shows sent, in flight, completed, failed,
needs review and rate. Details: [docs/architecture/09-bulk-send.md](docs/architecture/09-bulk-send.md).

### Review, currencies and batches

Transactions the system would not settle by guessing wait in the **Review** tab: check with the provider, send settle or an undo again under its
original key, and resolve as paid / not paid / written off with your name and a reason ([docs/architecture/11-review-workflow.md](docs/architecture/11-review-workflow.md)).
Requests carry a source and destination currency: priced from the market, sent only to providers that carry both, refused where a region forbids one
([docs/architecture/12-currencies.md](docs/architecture/12-currencies.md)). `mix replay reviews LOG` and `mix replay batches LOG` read both back from a log.

### Load and back-pressure

`mix load --count 2000 --admission none,150,100` sends a burst into a fresh demo world once per admission limit and prints how many
completed, failed or went to review (a 2,000 burst: 30% complete with no limit, 99.8% with a limit of 100). `mix demo --max-in-flight 100`
(or the admission panel on the Bulk send tab) sets the limit; production reads `ACTOR_TX_MAX_IN_FLIGHT` (default 100). Design:
[docs/architecture/10-back-pressure.md](docs/architecture/10-back-pressure.md); results: [docs/quality/LOAD_RESULTS.md](docs/quality/LOAD_RESULTS.md).

### The Test lab (design for test)

`mix demo --lab`, then open the **Test lab** tab (next to Live and Performance). It makes the hard-to-reach behaviours easy to provoke and to judge:

- **Scenarios**: 17 named scenarios, each with an expected outcome and hard checks (what each provider actually did, outcomes, residency). *Run* one, or *Run all*. Click a transaction id to follow its story on the Live tab. They are also an automated integration test.
- **Audit log**: make it *healthy*, *down*, or *fail after N steps* to see what each transaction state does (nothing started: fails cleanly; money moved: stops for a person); *kill the router* and watch the supervisor restart it; *verify logs + residency*.
- **Crash and recover**: *arm* a kill at an exact event (for example "when authorise succeeds", "when pay is sent"), submit a transaction, then *plan recovery (dry run)* to see what the log says about each open transaction, and *recover now*.
- **Idempotency**: submit the same id twice; re-send a completed step with the same key and check the provider did nothing more.
- **What the providers actually did**: the ground truth table the checks are judged against.

The header's submit controls (amount, merchant, region, instrument, customer type) stay visible, so you can set up any state by hand. Without `--lab` the tab explains how to enable it. Details: [docs/architecture/08-test-lab.md](docs/architecture/08-test-lab.md).

### The audit log, replay and recovery

```sh
mix demo --audit-dir audit/             # keep the regional logs
mix audit.verify audit/GLOBAL.log       # is the hash chain intact? (prints the head to anchor)
mix replay verify audit/GLOBAL.log      # did the day follow every rule?
mix replay txs audit/IN.log             # one line per transaction
mix replay at audit/GLOBAL.log --n 300  # the system as it stood at audit record 300
mix replay whatif audit/GLOBAL.log --set max_bps_from_best=25    # try a different policy on a real day
mix replay diff audit/GLOBAL.log other.log                       # compare two systems' days
mix replay residency audit/             # is every transaction recorded only where it may be?
```

In production the application refuses to start without an audit log (`require_audit`, see [config/prod.exs](config/prod.exs)). Compliance notes (PCI DSS, Dodd-Frank, eleven jurisdictions) are in [docs/compliance/COMPLIANCE_EVIDENCE.md](docs/compliance/COMPLIANCE_EVIDENCE.md).

### From IEx

```elixir
{:ok, fake} = ActorTxSpike.Provider.Fake.start_link(id: :alpha, rate: 1.08)
{:ok, server} = ActorTxSpike.Provider.Server.start_link(adapter: ActorTxSpike.Provider.Fake, config: fake, id: :alpha)
{:ok, policy} = ActorTxSpike.Policy.new(%{"weights" => %{"cost" => 1}})

ActorTxSpike.Events.subscribe()
# amounts are integer minor units: 100_000 is 1,000.00
{:ok, id} = ActorTxSpike.submit(%{amount: 100_000}, providers: [%{id: :alpha, server: server}], policy: policy)
ActorTxSpike.await(id)
flush()   # the story, as {:tx_event, %ActorTxSpike.Events.Event{}} messages
```

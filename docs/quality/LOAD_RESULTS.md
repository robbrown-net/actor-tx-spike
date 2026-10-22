# Load results

What happens when a burst of transactions meets the system, measured with `mix load`
(see [the developer guide](../DEVELOPER_GUIDE.md#2-the-commands-you-will-use)). Measured **2026-10-22** on one developer
laptop (single BEAM node) against the **fake demo providers**: five providers, each with a bulkhead of
10 concurrent calls and latencies of 90 to 180 ms at `--pace 0.2`. These numbers describe this model, not real
providers; the point is the shape, and a repeatable way to get the numbers again.

## Method

`mix load --count 2000 --admission none,400,200,150,100,75,50,25` sends 2,000 transactions with no pause
between them (spot, business, no region, so nothing is refused by jurisdiction; random amounts from 1.00 to 1,000.00)
into a fresh demo world, once for each admission limit, and waits for every one to end. The sender retries
a transaction the system refused as overloaded (*throttled*). Counts come from how each transaction ends.

## Results: admission limit (2,000 transactions, no pause)

| Admission limit | Completed | Failed | Needs review | Throttled (retried) | Seconds | Completed per second |
|---|---|---|---|---|---|---|
| none | 597 (30%) | 1,403 | 0 | 0 | 8.0 | 75 |
| 400 | 1,190 (60%) | 806 | 4 | 107 | 17.5 | 68 |
| 200 | 1,616 (81%) | 377 | 7 | 237 | 30.9 | 52 |
| 150 | 1,987 (99.4%) | 13 | 0 | 324 | 40.7 | 49 |
| **100** | **1,995 (99.8%)** | 1 | 4 | 462 | 51.9 | 38 |
| 75 | 2,000 (100%) | 0 | 0 | 493 | 55.2 | 36 |
| 50 | 2,000 (100%) | 0 | 0 | 576 | 63.2 | 32 |
| 25 | 2,000 (100%) | 0 | 0 | 734 | 84.7 | 24 |

Sender-side cap instead of server-side refusals: `mix load --count 2000 --admission 200 --sender-cap 120`
completed 1,999 of 2,000 in 45.8 s with **zero** refusals, because the sender never had more than 120 running.

## What it shows

1. **Without a limit the system does not break, it sheds.** 70% of a 2,000-transaction burst failed
   cleanly (nothing half-done, nothing paid twice), almost all with "no provider could give a usable quote": the providers'
   bulkheads were full for longer than a quote may wait. That is safe but it fails customers.
2. **A limit trades speed for completion.** Raising it lowers the completion rate; lowering it costs time. The knee
   for this model is **100 to 150**: above 150 failures climb quickly, at 100 and below nearly everything completes and each
   halving of the limit costs roughly 20 to 30% more time.
3. **Completed per second is highest with no limit, and that is misleading:** it counts the quick, cheap failures.
   Judge by completion rate first, then by time.
4. **The capacity is the quote fan-out.** Every transaction asks all five providers, so capacity is about
   (quote slots per provider ÷ provider latency) transactions per second, 60% of each bulkhead being available to quotes.
5. **A cap at the sender is gentler than refusals at the door** (no throttled retries, same completion),
   but only protects against that sender; the admission limit protects the system from everyone.

## What changed to get here

The first run of 1,000 randomised transactions (2026-10-21) completed 416 and sent 29 to `needs_review`. Tracing the
reasons (the Bulk send tab now shows them) found three causes, each addressed:

| Cause | Fix |
|---|---|
| Nothing limited how many transactions ran, so quote fan-out swamped the providers' bulkheads | [Admission control](../architecture/10-back-pressure.md): a limit on transactions in flight, refusing the excess as *overloaded* before anything starts; the sender backs off |
| A busy provider was given up on at once, for quotes and for steps | Quotes ask a busy provider again every 15 ms until the deadline; accept, authorise, pay and settle wait and ask again without using an attempt |
| Money-protecting calls (undo, status, settle) competed with new quote requests for the same slots, and several ended in `needs_review` | Bulkhead priority classes: quotes may use 60% of the slots, steps 100%, undo and status 150% |

After the fixes the same burst with no limit completes more (555 of 1,000 vs 416) with **0** `needs_review` (was 29) and
with a limit of 100 completes 1,000 of 1,000.

## Caveats

* Fake providers on one laptop: absolute numbers will differ with real providers, networks and hardware. Re-measure.
* One run per setting: differences of a few transactions between neighbouring rows are noise.
* The demo world has no audit log in these runs. The write-ahead barrier (a flush before every money-moving call) will
  add cost; measure with `--audit-dir` before choosing a production limit.
* Recovery-resumed transactions are not counted by admission control.
* The production default is 100 (`config/prod.exs`, override with `ACTOR_TX_MAX_IN_FLIGHT`), chosen from this table.

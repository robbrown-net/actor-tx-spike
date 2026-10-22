# User guide

For the person running and watching the system: an operator, a tester, a compliance reviewer or
someone learning how it behaves. No programming needed. (Developers: [DEVELOPER_GUIDE](DEVELOPER_GUIDE.md).
Testers who want the full picture of the checks: [TESTING](TESTING.md).)

## 1. What it does

You hand it a payment: an amount to convert and pay. It asks several third-party providers for an
FX quote, picks the best under your rules (price, speed, reliability, jurisdiction), accepts it,
authorises, pays and settles, and keeps a tamper-evident record of everything. If a provider
misbehaves it routes around it. If it cannot be sure a payment did or did not happen, it never
guesses: it stops and asks a person.

Everything here runs against five **fake** providers with different prices and speeds, so you can
break things safely.

## 2. Start it

```sh
export PATH="$PWD/.tooling/otp/bin:$PWD/.tooling/elixir/bin:$PATH"   # if using the local toolchain
mix demo --lab        # recommended: dashboard plus the Test lab
```

Open http://127.0.0.1:4040 (the dashboard only listens on your own machine).
Options (`mix demo ...`):

| Option | Effect |
|---|---|
| `--port 4545` | different port |
| `--auto` | start with automatic traffic |
| `--quiet` | do not print the story to the terminal |
| `--pace 0.2` | faster providers (1.0 is human-watchable; smaller is faster) |
| `--audit audit/day.log` | keep one audit log; open transactions in it resume at start |
| `--audit-dir audit/` | one log per region (GLOBAL, IN, CN); resumes open transactions |
| `--lab` | the Test lab tab, with regional logs in a fresh temporary directory |
| `--max-in-flight 100` | back-pressure: refuse submissions beyond 100 running transactions |

## 3. The dashboard

Four tabs: **Live**, **Performance**, **Bulk send** and **Test lab**.

**Live.** The five providers (price, speed, health state, circuit breaker), the transactions in
flight and finished, the **Story** pane (what each component is doing, in plain words), and the
**Economics** panel (what the system cost, what better rates saved, margin against guarantees).
Click a transaction to follow just its story; *show all* clears the filter.

**Header controls.** Amount, merchant, customer, **region**, **instrument** (spot or forward), **customer
type** (business or retail) and the **From** and **To** currencies, then *Submit transaction*. The currencies are shown in the story and recorded; they do not yet change pricing or routing. *Auto traffic* submits transactions for
you. Region matters: *IN* sends records only to the India log and allows only India-eligible
providers; *CN* likewise for China; forwards to IN and retail to CN are refused before any
provider is asked.

**Breaking providers.** On each provider card, buttons change its behaviour:

| Button | What the provider does |
|---|---|
| slow, stall | answers 4× / 12× slower |
| error, reject | fails or refuses quote requests |
| authorise fails | refuses to authorise (the accept is undone, another provider is used) |
| pay answer lost | makes the payment but the reply is lost once (the system asks, and does not repeat) |
| pay vanishes | never answers about the payment (the transaction stops for a person, nobody else is paid) |
| settle fails / vanishes | refuses or never confirms settlement |
| version drift | silently changes version (the sentinel quarantines it) |
| reprice | changes its rate |
| heal / release | back to normal |

**Policy and cut-offs.** *Apply policy* changes the routing weights (cost, speed, reliability) and
limits. *Set cut-off for region* sets a settlement deadline some seconds ahead, to see a
transaction stop and undo when it passes; *Clear* removes it.

**Reading a transaction's story.** Typical order: *received*, eligibility, policy, deadline,
quotes asked and answered, *selected X*, accept, authorise, pay, settle, *completed*. A transaction ends
`completed`, `failed` (cleanly: nothing left half done) or `needs_review` (a person must decide;
money may have moved).

**Performance.** CPU, memory, network, scheduler load, run queue, throughput and end-to-end
latency, per second and rolled up per minute.

## 4. Bulk send

The **Bulk send** tab sends many transactions for you. It works with a plain `mix demo`.

* **Batch:** how many (buttons for 10, 100, 1,000 and 10,000, or any number from 1 to 10,000), the
  smallest and largest amount (each transaction gets a random amount in that range), the pause
  between transactions in milliseconds (0 sends as fast as it can), and an optional **seed**.
* **Send with the header settings:** every transaction uses the merchant, customer, region, type
  and currencies chosen in the header; only the amount varies.
* **Randomise everything:** every transaction draws its own merchant, customer, origin and
  destination region, instrument, customer type and currencies at random. Untick a setting to keep it
  as in the header. Some random combinations are refused on purpose (a forward to IN, a retail customer to CN):
  they show as *failed* because the system refused them before any provider was asked. A seed makes a random batch repeat exactly.
* **Shuffle the header settings:** sets the header controls to random values once, so you can submit a single random transaction by hand.
* **Progress:** state (*running*, *draining* while the last ones finish, *finished*, *stopped*), a bar, and
  sent, in flight, completed, failed (including refused), needs review, submit errors, elapsed time and
  rate. *Stop* ends the sending (transactions already sent finish normally); *Clear* forgets a finished batch.
  Click a latest transaction id to see its story on the Live tab.

* **Sender waits at N in flight:** the sender pauses while that many of its own transactions are still running (0 = no limit).
* **Back-pressure: admission limit:** the most transactions the system will run at once. Beyond it a submission is refused
  as *overloaded* before anything starts, and the sender waits and tries again (the *throttled* count). Type a number and
  press *Apply*, or *No limit*. The panel shows how many are running, admitted, refused and the peak.
* **Why they did not complete:** the progress panel groups the failure and review messages and counts them.

One batch runs at a time. With **no limit**, a large burst with no pause overwhelms the demo providers: in a 2,000-transaction
test only 30% completed (the rest failed cleanly). With a limit of 100 to 150, 99% or more completed. Try both and watch the
numbers. Details: [architecture/09](architecture/09-bulk-send.md), [architecture/10](architecture/10-back-pressure.md) and the measurements in
[quality/LOAD_RESULTS](quality/LOAD_RESULTS.md).

### Measuring from the command line

```sh
mix load --count 2000 --admission none,200,150,100,50     # one run per limit, then a table
mix load --count 2000 --admission 200 --sender-cap 120    # the sender holds itself to 120 in flight
```

Add `--pace 0.2` (provider speed), `--mode random --seed 7` (randomised settings), `--interval MS`. It prints sent, completed,
failed, needs review, throttled, refused, peak, seconds and the reasons for each run.

## 5. The Test lab

`mix demo --lab`, tab **Test lab**. It lets you provoke the hard situations and see a verdict.

* **Scenarios**: 17 ready-made situations, each with what to expect and checks with evidence. *Run*
  one or *Run all*. Click a transaction id to see its story on the Live tab. Groups: jurisdiction
  routing, idempotency, provider faults, audit-log downtime, restarts and the router.
* **Audit log**: *Healthy*, *Down*, or *Fails after N steps*. See what each transaction state does
  when the record cannot be kept: a transaction that has not started fails cleanly; one that has
  moved money stops for a person. *Kill the router* and watch the supervisor bring it back.
  *Verify logs + residency* checks every log's chain, every transaction's rules and where each
  transaction is recorded.
* **Crash and recover**: *Arm* a crash at an exact moment (for example "when authorise succeeds"),
  submit a transaction, then *Plan recovery (dry run)* to see what the log says about each open
  transaction, then *Recover now*.
* **Idempotency**: *Submit it twice* (same id) and *Send again, same key* (re-send a completed step):
  the provider must do nothing more.
* **What the providers actually did**: the ground truth the checks are judged against.

Without `--lab` the tab explains how to turn it on. More detail: [architecture/08](architecture/08-test-lab.md).

## 6. The audit log: verify, replay, recover

Run with a kept log (`mix demo --audit-dir audit/`), then:

```sh
mix audit.verify audit/GLOBAL.log                # is the hash chain intact? prints the head to anchor
mix replay verify audit/GLOBAL.log               # did the day follow all 11 rules? (non-zero exit if not)
mix replay txs audit/IN.log                      # one line per transaction
mix replay at audit/GLOBAL.log --n 300           # the system as it stood at audit record 300 (or --ms, --seq)
mix replay whatif audit/GLOBAL.log --set max_bps_from_best=25    # a different policy on a real day
mix replay diff audit/GLOBAL.log other.log       # compare two systems' days
mix replay residency audit/                      # is every transaction recorded only where it may be?
```

* **Verify** shows whether anyone altered the record. **Replay** re-judges the day with the same
  decision code that made it. **What-if** says how many transactions would route differently and the net
  effect (quote-level: it assumes the alternative would have completed).
* **Restart:** start again with the same `--audit` or `--audit-dir`. Transactions that were open are
  resumed from where the log says they stood, never paid twice; anything the log cannot settle goes to
  `needs_review`. Messages beginning `recovery:` appear in the terminal.
* **A log that fails verification** is refused: the system will not append to a damaged record.

## 7. Production settings

The application refuses to start in production without an audit log. Set:

| Variable | Meaning | Default |
|---|---|---|
| `ACTOR_TX_AUDIT_DIR` | directory for the logs | `audit` |
| `ACTOR_TX_AUDIT_LOGS` | comma-separated log names, covering every log the jurisdiction rules name | `GLOBAL` |
| `ACTOR_TX_MAX_IN_FLIGHT` | most transactions running at once; more are refused as *overloaded* | `100` |

Note the dashboard has no authentication or TLS: keep it on the loopback interface.

## 8. Jurisdictions

The eleven jurisdictions in scope are UK, EU, US, India, Mexico, Brazil, Turkey, Saudi Arabia, Japan,
South Korea and China, for spot and forward, business and retail. The demo implements rules for
IN and CN (local-only logs and provider restrictions) and retention periods for UK and EU. The
country-by-country picture, what evidence exists and what is missing, is in
[compliance/COMPLIANCE_EVIDENCE](compliance/COMPLIANCE_EVIDENCE.md). The rules are unverified by
counsel: do not treat them as legal advice.

## 9. Troubleshooting

| You see | Likely cause | What to do |
|---|---|---|
| Every transaction fails with `audit_unavailable` | the audit log cannot be written (Test lab: fault set to *Down*, or a real disk problem) | set the log *Healthy* in the lab, or fix the disk; nothing moved |
| Transaction in `needs_review` | a payment or settlement could not be confirmed, or the log failed after money moved | read its story; the review workflow is not built yet, so check the provider by hand |
| The system will not start on a log | its hash chain is broken, or another process holds `<log>.lock` | `mix audit.verify`; stop the other writer |
| "no such log" / transaction refused for a region | the rules name a log that was not configured | add it to `--audit-dir` setup / `ACTOR_TX_AUDIT_LOGS` |
| Provider shows `quarantined` | version drift was detected | *heal* it, or leave it out of routing |
| Test lab tab says to enable the lab | started without `--lab` | restart with `mix demo --lab` |
| A submission is refused as `overloaded` | the admission limit is full: the system is protecting itself | wait the suggested time and submit again (the Bulk sender does this for you), or raise the limit if the providers can take more |
| Many transactions fail with "no provider could give a usable quote" | no admission limit and the providers are saturated | set a limit (try 100 to 150) |
| "a batch is already running" | one batch runs at a time | press *Stop*, then *Clear* if you want a fresh one |
| Many failed in a random batch | refusals by jurisdiction (forward to IN, retail to CN) and, at high speed, provider overload | untick *instrument*, *customer type* and the regions to avoid refusals; add a pause |
| "not a currency code" | a currency must be three capital letters | use the lists in the header |

## 10. Words used

*Quote*: a provider's offered rate and fee. *Net*: what the customer ends up with (amount × rate − fee).
*Failover*: moving to the next provider after a definite failure. *Doubt*: a call whose outcome is
unknown. *Compensation*: undoing what was done at a provider. *Guarantee*: a merchant's promised rate for
a period. *Cut-off*: the regional deadline for settlement. *Residency*: records stay in the regions they
must. *Batch*: a run of many transactions sent from the Bulk send tab. *Seed*: a number that makes a random batch repeat exactly. Full glossary: [architecture/02](architecture/02-domain-model.md).

# 01 · Use cases (Cockburn style)

Fully-dressed use cases in Alistair Cockburn's format, written at *user-goal* level
unless stated. The system under discussion (SuD) is **ActorTxSpike**, treated as a black box.

Status legend: ✅ implemented · 🟡 partly · 🔲 planned

## Actors and stakeholders

| Actor | Kind | Interest |
|---|---|---|
| Merchant system | primary (ingress) | Get a customer's payment converted and paid, fast and at a good rate, exactly once |
| Operator | primary | See what is happening, change conditions, intervene |
| Finance / compliance reviewer | primary (planned) | Resolve anything the system could not safely decide |
| Load generator (operator) | primary | Send many varied transactions, at a chosen pace, and see how the system copes |
| Tester / developer | primary | Provoke the hard-to-reach behaviours (restart, idempotency, router, audit-log downtime) and judge the outcome, without writing code |
| Auditor / QSA | primary | Evidence that the system did the right things, and that the evidence has not been altered |
| Provider (FX / payment third party) | supporting | Receives requests; we do not control it |
| Sentinel (timer) | supporting | Keeps our knowledge of providers fresh |
| Customer | stakeholder | Is paid once, correctly, on time |
| Business owner | stakeholder | Margin, risk, auditability, what the system costs to run and what better rates earn |

## UC-1 Complete an FX swap and payment ✅

| | |
|---|---|
| **Goal level** | User goal (sea level) |
| **Primary actor** | Merchant system |
| **Scope** | ActorTxSpike |
| **Stakeholders and interests** | Merchant: correct, timely, cheap. Customer: paid exactly once. Business: no unrecoverable partial states, full audit trail. Providers: idempotent, bounded call rates. |
| **Preconditions** | At least one provider is registered; a routing policy is in force. |
| **Minimal guarantee** | The customer is **never paid twice**. Every effect that was accepted or authorised at a provider is either undone or escalated to review. Every step is recorded in the narrative. |
| **Success guarantee** | Exactly one payment executed by the best viable provider under the policy; receipt and rationale available. |
| **Trigger** | Merchant submits a transaction request (amount, id). |

**Main success scenario**

1. Merchant submits the request.
2. System acknowledges with a transaction id and starts the transaction.
3. System asks every callable provider for a quote concurrently, within a deadline.
4. System discards unusable quotes and ranks the rest under the policy.
5. System selects the best candidate.
6. System asks that provider to **accept** the quote (rate lock).
7. System asks that provider to **authorise** the payment (funds hold).
8. System asks that provider to **pay**.
9. System asks that provider to **settle** the payment (before the regional cut-off).
10. System records completion, margin against any standing guarantee, and informs waiters; Merchant reads the outcome.

**Extensions**

| At | Condition | Handling |
|---|---|---|
| 3a | Provider is stalled / quarantined | Not called; reason recorded. |
| 3b | Provider times out, errors or rejects | Excluded with reason; others continue. |
| 4a | Quote worse than best by more than the policy limit (bps) | Excluded. |
| 4b | Quote expires too soon / provider too slow | Excluded by constraint. |
| 5a | No usable quote | Outcome `failed: no_quote`, or baseline route returned if supplied. |
| 6–8a | Technical error | Retried (same key) up to the attempt limit. |
| 6a | Provider rejects accept | Fail over to next candidate (nothing to undo). |
| 7a | Provider rejects authorise | Undo accept, then fail over. |
| 8a | Provider rejects pay | Void authorisation, cancel accept, fail over. |
| 6–7b | Answer lost / timeout | Ask provider what it holds. Found: continue. Not confirmable: cancel by key, fail over. |
| 8b | Pay answer lost / timeout | Ask provider repeatedly. Found: complete. Unresolved: **stop in `needs_review`, never fail over.** |
| 6–8c | Undo itself cannot be completed | Escalate to `needs_review`. |
| any | Candidate's quote expired before its turn | Skipped. |
| 9a | Settlement refused or keeps failing | `needs_review {:settlement_failed}`: the payment was made and is **not** undone. |
| 9b | Settlement answer lost | Reconcile by status; unresolved → `needs_review {:in_doubt, :settle}`. |
| any | Regional cut-off passes before payment | Stop, undo what was done, `failed {:deadline_missed, region}`. |
| 9c | Cut-off passes after payment, before settlement | `needs_review {:settlement_overdue, region}`. |
| 10a | Falls short of a standing guarantee | Exposure recorded in the ledger and narrated loudly. |
| any | All candidates exhausted | Outcome `failed: exhausted`, all effects undone. |

## UC-2 Observe and diagnose the running system ✅

| | |
|---|---|
| **Primary actor** | Operator |
| **Goal level** | User goal |
| **Minimal guarantee** | Observing never alters or slows transaction processing. |
| **Success guarantee** | Operator can follow any transaction's story, see provider health and see current policy. |
| **Main scenario** | 1. Operator opens the dashboard. 2. System replays recent narrative and streams live events. 3. Operator reads provider cards, transaction pipelines and the story. 4. Operator selects a transaction to isolate its story. |
| **Extensions** | 2a. Stream drops → dashboard reconnects automatically. 3a. Operator submits traffic, switches auto-traffic, changes pace, edits the policy or injects a fault (slow, stall, error, reject, authorise fails, pay answer lost, pay vanishes, drift, release, heal). |

## UC-3 Keep provider knowledge honest (sentinel) 🟡

| | |
|---|---|
| **Primary actor** | Sentinel (timer) |
| **Goal level** | Subfunction |
| **Success guarantee** | Provider health, latency and version reflect reality within one probe interval. |
| **Main scenario** | 1. Every configured interval the sentinel probes each provider. 2. Result updates latency/error statistics. 3. A changed version quarantines the provider until reviewed. 4. A successful half-open probe begins gradual recovery. |
| **Status** | Probing, drift quarantine and recovery are implemented and demonstrated; the *configurable, persistent, schema-fingerprinting* sentinel is roadmap Phase 3. |

## UC-4 Resolve a transaction needing review 🔲

| | |
|---|---|
| **Primary actor** | Finance / compliance reviewer |
| **Success guarantee** | Every `needs_review` transaction is resolved to exactly one of: confirmed paid, confirmed unpaid and undone, or refunded, with an audit record. |
| **Main scenario** | 1. Reviewer opens the queue. 2. Sees the story and provider evidence. 3. Re-queries provider status. 4. Chooses an action. 5. System executes with idempotency keys and records the decision. |
| **Status** | Escalation exists (`needs_review` state and narrative); review tooling does not. |

## UC-5 Tune routing policy ✅

| | |
|---|---|
| **Primary actor** | Operator (global), Merchant (paid override), special customer |
| **Main scenario** | 1. Actor edits weights (cost / speed / reliability) and constraints (min validity, max latency, max bps from best, excluded states). 2. System validates; invalid policy is rejected with every error. 3. New transactions use the new policy. |
| **Status** | Implemented: global → merchant (only if entitled) → customer cascade with partial overrides; every layer validated when the set is built; the layers used are narrated per transaction. Standing guarantees are accounted in a ledger. Who may edit which layer (authorisation) and an audit trail are not built. |

## UC-6 Monitor performance and resource use ✅

| | |
|---|---|
| **Primary actor** | Operator |
| **Success guarantee** | Operator can see memory, CPU, scheduler load, network, throughput and latency over time without the monitoring itself becoming a cost. |
| **Main scenario** | 1. Operator opens the Performance view. 2. Charts update every 1.5 s from `GET /api/metrics`. 3. Operator picks a window (1 / 5 / 15 min or one-minute rollups). |
| **Not yet** | Long-term storage metrics (no durable store), alerting, retention beyond the in-memory rollups. |

## UC-7 Audit a transaction and prove the record is intact ✅ (partly)

| | |
|---|---|
| **Primary actor** | Auditor / compliance reviewer (operator runs the tool) |
| **Preconditions** | The system ran with the audit log enabled (`mix demo --audit PATH`, or `Audit` started by a supervisor). |
| **Success guarantee** | For any transaction: every event from `tx_received` to its outcome, with quoted rate and fee, alternatives, provider calls, failures, compensations and the measured cost, in order and time-stamped; and a yes/no answer on whether the log was altered. |
| **Main scenario** | 1. Auditor asks for a transaction's records (filter the log by `tx`). 2. Auditor runs `mix audit.verify PATH`. 3. System checks sequence, every chain link and every hash and reports the record count and head hash. 4. Auditor compares the head with the independently anchored value. |
| **Extensions** | 3a. A record was edited, removed, inserted or reordered: verification stops at the first affected line and says which and why. 3b. The log is malformed: reported as such. 4a. Head is shorter than the anchor: the tail was truncated. |
| **Special requirements** | Card-like numbers (Luhn-valid, 13-19 digits) are redacted before they reach disk. The log refuses to start on top of a log that fails verification. |
| **Status** | Implemented: capture, chain, verify, scrub. **Not built:** supervision and fail-closed behaviour, retention and rotation, off-host copy, user identity on records, a per-transaction retrieval command. See [compliance evidence](../compliance/COMPLIANCE_EVIDENCE.md). |

## UC-8 Understand what the system costs and what it saves ✅

| | |
|---|---|
| **Primary actor** | Operator, finance |
| **Success guarantee** | For every finished transaction and in total: what it cost to run (CPU, memory, network, storage, provider calls), what the chosen rate saved against the median, worst and best quote, any margin or exposure against a guarantee, and the net after cost. |
| **Main scenario** | 1. A transaction finishes. 2. System measures what it consumed and records how the chosen price compares with the quotes. 3. System prices the measurements with the rate card and announces the result in the narrative. 4. Operator opens the Performance view and reads the Economics panel (totals and per provider). |
| **Extensions** | 2a. The transaction failed or needs review: no value is credited, its cost still counts. 2b. Only one provider quoted: savings are zero by construction. 3a. Prices change: re-price from the raw measurements in the audit log. |
| **Special requirements** | The baseline for "saved" (the median quote) is always stated; the price paid for speed or reliability ("regret vs best") is shown, not hidden. |
| **Status** | Implemented with placeholder prices. Per-transaction attribution covers the orchestrator process only. |

## UC-9 Replay the day ✅ (first version)

| | |
|---|---|
| **Primary actor** | Operator, auditor, strategy owner |
| **Preconditions** | The day was run with the audit log on. The log verifies. |
| **Success guarantee** | From the log alone, without the live system: the exact conditions at any moment; a yes or no on whether every rule held, with the evidence for each breach; a transaction-by-transaction comparison with another system's log; and the effect of a changed strategy on the same day. |
| **Main scenario** | 1. Actor loads the log; the chain is verified first. 2. Actor asks for the state at a moment (record number, sequence or time). 3. Actor verifies the day; each rule is checked per transaction. 4. Actor re-runs the day with a changed policy and reads what routes change and the net and latency effect. 5. Actor compares the day with another system's log. |
| **Extensions** | 1a. Chain broken: replay refuses to start and says where. 3a. A rule was broken: the report names the rule, transaction and audit record. 4a. The change is not a valid policy: refused with every error. 5a. A transaction exists in only one log: listed separately. |
| **Special requirements** | Decisions are re-judged with the same code that made them live. Timing is not compared between systems. |
| **Status** | Implemented as a library and `mix replay`. **Not built:** re-executing the orchestrator against recorded answers (virtual clock, replay provider), capturing the cascade layers and the rate card, retention and tiering of a day's log. What-if is quote-level (see ADR 0009). |

## UC-10 Resume after a restart ✅ (first version)

| | |
|---|---|
| **Primary actor** | Operator (the restart is the trigger) |
| **Preconditions** | The previous run kept the audit log(s). Providers are registered again. |
| **Success guarantee** | Every transaction that was open is resumed from where its log says it stood; none is paid twice; anything the log cannot settle goes to a person. |
| **Main scenario** | 1. System starts; the logs are verified and loaded. 2. System lists the open transactions and, for each, where it stood. 3. Each is resumed: quoting again, sending the step that was never sent, asking the provider about a step that may have been sent, finishing undos, or recording a completion. 4. Resumed work is recorded in the same log, chained after the crash. |
| **Extensions** | 1a. A log fails verification: the system refuses to start on it. 2a. The same transaction is in several regional logs: resumed once. 3a. The provider it was using no longer exists, or its quote was never recorded: escalated to a person. 3b. A pay or settle cannot be confirmed: never failed over; a person decides. 3c. The cut-off has passed: it undoes what it did and stops. |
| **Special requirements** | A call in the log is reconciled, never repeated blindly; a call not in the log is safe to make (write-ahead). |
| **Status** | Implemented, proven by cut-point, random-kill and restart tests. The audit router is supervised and production refuses to start without it (`require_audit`). Recovery is started by the host (as `mix demo` does), not automatically by the application, because it needs the live provider registry. See ADR 0010. |

## UC-11 Keep a transaction inside its jurisdiction ✅ (mechanism)

| | |
|---|---|
| **Primary actor** | Compliance owner (sets rules), merchant system (submits) |
| **Success guarantee** | A transaction uses only providers eligible for its corridor, is refused if its instrument or customer type is not permitted there, and its records live only where the rules allow; all of it provable from the logs. |
| **Main scenario** | 1. A request names origin and destination regions, instrument and customer type. 2. System decides eligibility from the rules, strictest region winning. 3. Ineligible providers are not asked. 4. The decision is recorded. 5. Every event of the transaction is written to the logs the decision names. 6. Auditor runs the residency check across the regional logs. |
| **Extensions** | 2a. Instrument or customer type not permitted: the transaction fails before any provider is called, with the reasons. 3a. No provider is eligible: fails with `no_eligible_provider`. 5a. The decision names a log the system does not have: the router stops (fail closed). 6a. A transaction is found in a log it must not be in, or missing from one it must be in: reported. |
| **Status** | Implemented as mechanism. The rules themselves need counsel per country; retention is recorded but not enforced; sanctions and KYC gates are not built. See ADR 0011. |

## UC-12 Test the hard-to-reach behaviours ✅

| | |
|---|---|
| **Primary actor** | Tester / developer |
| **Preconditions** | `mix demo --lab` is running (regional logs in a fresh temporary directory, under a supervisor). |
| **Success guarantee** | For the router, idempotency, restarts and audit-log downtime, the tester can put the system in a chosen state, see what it did, and see a pass/fail verdict backed by what the providers actually did and by the logs. |
| **Main scenario** | 1. Tester opens the Test lab tab. 2. Tester runs a scenario (or all 17), or builds a situation by hand: sets the audit log healthy, down or failing after N steps; arms a crash at an exact event; submits transactions. 3. System shows each check with its evidence. 4. Tester asks for a recovery plan (dry run), then recovers. 5. Tester verifies every log's chain, every transaction's rules, and residency. |
| **Extensions** | 2a. Tester submits the same transaction id twice, or re-sends a completed step with the same key: the provider does nothing more. 2b. Tester kills the audit router: money stops while it is down; the supervisor restarts it. 2c. The lab is not enabled: the tab explains how to start it and scenarios needing the logs are skipped. |
| **Special requirements** | Scenarios are deterministic (prices fixed, providers healed, healthy log restored before each). The scenario library is also an automated integration test. |
| **Status** | Implemented. See [08](08-test-lab.md). Known limit: the crash trigger hits the next matching event of any transaction. |

## UC-13 Send many transactions ✅

| | |
|---|---|
| **Primary actor** | Operator or tester |
| **Preconditions** | `mix demo` is running. |
| **Success guarantee** | The requested number of transactions is submitted at the requested pace, with amounts in the requested range, and every one is accounted for (completed, failed or needs review). |
| **Main scenario** | 1. Operator opens the Bulk send tab. 2. Operator sets the count (for example 10, 100, 1,000 or 10,000), the smallest and largest amount, and the pause between transactions in milliseconds. 3. Operator sends the batch with the header's settings, or chooses *Randomise everything* so each transaction draws its own merchant, customer, regions, instrument, customer type and currencies. 4. System submits one transaction per pause and shows progress: sent, in flight, completed, failed, needs review, rate. 5. System finishes when every transaction has ended. |
| **Extensions** | 3a. A seed is given: the random batch repeats exactly. 3b. The plan is invalid (count out of range, minimum above maximum, unknown setting): refused with every reason. 4a. Operator stops the batch: sending ends, transactions already sent finish normally. 4b. A batch is already running: a second is refused. 4c. A randomised combination is not permitted (forward to IN, retail to CN): the transaction is refused before any provider is asked and counted as failed. |
| **Special requirements** | A transaction submitted by a batch is indistinguishable from one submitted by hand (same eligibility, journal, audit log and economics). |
| **Status** | Implemented. See [09](09-bulk-send.md). Not built: a record of batch parameters in the audit log (batches are not replayable as batches, only as the transactions they made), a target rate with automatic back-off, and a cap on in-flight transactions. |


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
| Provider (FX / payment third party) | supporting | Receives requests; we do not control it |
| Sentinel (timer) | supporting | Keeps our knowledge of providers fresh |
| Customer | stakeholder | Is paid once, correctly, on time |
| Business owner | stakeholder | Margin, risk, auditability |

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

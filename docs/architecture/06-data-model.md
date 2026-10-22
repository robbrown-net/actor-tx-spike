# 06 · Data model

**Honest status:** nothing is persisted today. These are the entities as they exist
in process memory and in the event stream, followed by the logical model the durable
store (roadmap Phase 3) should implement. `PLANNED` marks things that do not exist yet.

## 6.1 Logical entity-relationship model

```mermaid
erDiagram
    MERCHANT ||--o{ TRANSACTION : submits
    CUSTOMER ||--o{ TRANSACTION : "is paid by"
    POLICY ||--o{ TRANSACTION : "resolved for"
    MERCHANT ||--o{ POLICY : "may override (PLANNED)"
    MERCHANT ||--o{ STANDING_GUARANTEE : "may hold"
    STANDING_GUARANTEE ||--o{ LEDGER_ENTRY : "accounted by"
    TRANSACTION ||--o| LEDGER_ENTRY : "settles against"
    TRANSACTION }o--o| CUTOFF : "must finish before"
    TRANSACTION ||--o{ QUOTE : receives
    PROVIDER ||--o{ QUOTE : issues
    QUOTE ||--o| CANDIDATE : "becomes if admissible"
    TRANSACTION ||--o{ EFFECT : causes
    PROVIDER ||--o{ EFFECT : performs
    PROVIDER ||--|| PROVIDER_HEALTH : "is modelled by"
    PROVIDER ||--o{ HEALTH_SAMPLE : "yields"
    TRANSACTION ||--o{ NARRATIVE_EVENT : "narrated by"
    PROVIDER ||--o{ NARRATIVE_EVENT : "narrated by"
    TRANSACTION }o--o| SETTLEMENT_BATCH : "settled in (PLANNED)"

    TRANSACTION {
        string id PK
        string request_id
        decimal amount
        string phase
        string outcome
        string chosen_provider FK
        datetime started_at
        datetime ended_at
    }
    POLICY {
        string id PK
        json weights
        int min_validity_ms
        int max_latency_ms
        float max_bps_from_best
        json exclude_states
    }
    PROVIDER {
        string id PK
        string adapter
        string version
    }
    PROVIDER_HEALTH {
        string provider_id PK
        string state
        string breaker
        float error_rate
        int p95_ms
        boolean quarantined
        int recovering
    }
    HEALTH_SAMPLE {
        string provider_id FK
        datetime at
        string outcome
        int latency_ms
    }
    QUOTE {
        string provider_id FK
        string transaction_id FK
        float rate
        float fee
        datetime expires_at
    }
    CANDIDATE {
        string transaction_id FK
        string provider_id FK
        float net
        int latency_ms
        float score
        int rank
    }
    EFFECT {
        string idempotency_key PK
        string transaction_id FK
        string provider_id FK
        string operation
        string status
        json receipt
        datetime at
    }
    NARRATIVE_EVENT {
        int seq PK
        datetime at
        string transaction_id FK
        string actor
        string kind
        string message
        json data
    }
    STANDING_GUARANTEE {
        string id PK
        string merchant_id FK
        string period
        float guaranteed_rate
    }
    LEDGER_ENTRY {
        string tx_id PK
        string merchant_id FK
        string period
        float guaranteed_net
        float achieved_net
        float margin
    }
    CUTOFF {
        string region PK
        int hour
        int minute
        string utc_offset_rule
    }
    SETTLEMENT_BATCH {
        string id PK
        string region
        datetime cutoff
        string status
    }
```

## 6.2 Where each entity lives today

| Entity | Today | Planned |
|---|---|---|
| Transaction | `Payment` process state (lingers 30 s after finishing) | Event-sourced log; projections for status |
| Quote / Candidate / Selection | In the `Payment` process and the narrative `data` | Persisted with the decision rationale |
| Effect | Provider-side only (in the fake); our side is in the narrative | Own table keyed by idempotency key (outbox/inbox) |
| ProviderHealth / HealthSample | `Provider.Server` state (rolling window of 20) | Time series with retention (see [07](07-observability-and-performance.md)) |
| NarrativeEvent | `Events` ring buffer, 1,000 events | Append-only log, partitioned, retention policy |
| StandingGuarantee, LedgerEntry | `Guarantee` struct; `Ledger` process (in memory, idempotent per transaction) | Durable ledger |
| Cutoff | `Cutoff` struct supplied per region | Reference data with a timezone database |
| Policy layers (merchant, customer) | `PolicySet` value | Versioned, audited configuration |
| Merchant, Customer, SettlementBatch | merchant and customer exist only as ids in requests / policy layers | Phase 3 |

## 6.3 Invariants the model must keep

1. At most one `pay` effect per transaction.
2. An effect row is unique per `idempotency_key`.
3. A transaction's `chosen_provider` is the provider of its `pay` effect when `outcome = completed`.
4. A `failed` transaction has no `pay` effect and every `accept` / `authorise` has a matching undo.
5. `needs_review` is the only state that may hold un-undone effects, and it must carry the reason.
6. Narrative `seq` is strictly increasing; events are never edited.

Invariants 1, 3 and 4 are executable today: see
`test/integration/payment_invariants_test.exs`.

## Evidence and economics (added 2026-10-17)

Money is held as integers (ADR 0008): amounts and fees in minor units, rates scaled by 10^8,
costs in pico-units.

```mermaid
erDiagram
    TRANSACTION ||--o{ AUDIT_RECORD : "narrated as"
    AUDIT_RECORD ||--o| AUDIT_RECORD : "prev hash commits to"
    TRANSACTION ||--|| ECONOMICS_RECORD : "measured as"
    ECONOMICS_RECORD ||--|| COST_BREAKDOWN : "priced into"
    ECONOMICS_RECORD ||--o| PRICE_DISCOVERY : "compares quotes"
    RATE_CARD ||--o{ COST_BREAKDOWN : "prices"

    AUDIT_RECORD {
        int n "gapless sequence"
        int seq "event stream sequence"
        int at "ms, system clock"
        string tx
        string actor
        string kind
        string message "scrubbed of card-like numbers"
        json data "scrubbed"
        string prev "hash of record n-1"
        string hash "sha256(prev + body)"
    }
    ECONOMICS_RECORD {
        string tx
        string outcome "completed, failed, needs_review"
        int reductions
        int memory_bytes
        int duration_ms
        int provider_calls
        int bytes_out
        int bytes_in
        int state_changes
        int compensations
        int margin "minor units, negative is exposure"
        int provider_fee "minor units"
    }
    PRICE_DISCOVERY {
        int quotes
        int chosen_net "minor units"
        int median_net
        int worst_net
        int best_net
    }
    COST_BREAKDOWN {
        int cpu "pico-units"
        int memory
        int network
        int storage
        int provider_calls
        int total
    }
    RATE_CARD {
        int cpu_picos_per_million_reductions
        int memory_picos_per_gib_second
        int network_picos_per_gib_out
        int state_change_picos
        int provider_call_picos
        int platform_picos_per_hour
    }
```

## Journal schema v1 (added 2026-10-17)

> Unchanged by the 2026-10-20 refactor. A recorded quote is rebuilt in exactly one place,
> `Replay.Tx.quotes/1` / `quote_from/1`, which both replay and recovery use.

Journal events live in the same stream and audit log as the narrative (actor `journal`).
Together with the existing narrative kinds they are the replay script.

```mermaid
erDiagram
    TRANSACTION ||--|| TX_RECEIVED : "starts with"
    TRANSACTION ||--o{ QUOTE_OBSERVED : "one per provider asked"
    TRANSACTION ||--o{ PROVIDER_RESPONSE : "one per provider call"
    TX_RECEIVED {
        json request "id, amount, merchant, customer, region"
        json policy "weights, min_validity_ms, max_latency_ms, max_bps_from_best, exclude_states"
        json guarantee "merchant, period, rate (scaled)"
        json config "attempts, backoff, timeouts, deadline"
        list providers
    }
    QUOTE_OBSERVED {
        string provider
        string outcome "quote, rejected, error, timeout"
        int rate "scaled 1e8"
        int fee "minor units"
        int expires_at_ms
        string state "health state when asked"
        int latency_ms
        int observed_at_ms
    }
    PROVIDER_RESPONSE {
        string provider
        string kind "step, status, undo"
        string op
        string key "tx:provider:step"
        int attempt
        string class "ok, error, rejected, timeout, in_doubt, not_found"
        int latency_ms
    }
```

Also journalled: `control` (operator and world actions, with their arguments) and the
provider `health` events. Decisions are recorded by the existing `selection_made`
(ranking and scores), `provider_chosen`, `step_*`, `failover`, `undone`, `deadline_set`,
guarantee and `tx_economics` events.

## Jurisdiction, recovery and the lock (added 2026-10-17)

`tx_received` now also carries the eligibility decision; `tx_resumed` records a recovery.

```mermaid
erDiagram
    TX_RECEIVED ||--|| ELIGIBILITY_DECISION : "records"
    TX_RECEIVED ||--o| TX_RESUMED : "may be followed by"
    ELIGIBILITY_DECISION {
        bool permitted
        list reasons "why not"
        list regions "origin and destination"
        string instrument "spot, forward"
        string customer_type "business, retail"
        string source_currency "if the request names one"
        string destination_currency "if the request names one"
        list allowed_providers
        list denied_providers "provider and reason"
        list logs "where records must live"
        int retention_days "longest that applies"
    }
    TX_RESUMED {
        string mode "fresh, next, doubt, undo, complete, review"
        string provider
        string op
        list done
        json eligibility "so the router re-learns the logs"
    }
```

Files per log directory: `<region>.log` (the hash chain) and `<region>.log.lock`
(`"<os pid> <erlang pid>"`, present while a writer holds the log).

## Review and batch records (added 2026-10-23)

A transaction that went to review gains records after its costing, in the same regional logs; a bulk batch leaves a start and an end record
(with no transaction).

```mermaid
erDiagram
    TX_NEEDS_REVIEW ||--o{ REVIEW_CHECKED : "a person asked the provider"
    TX_NEEDS_REVIEW ||--o{ REVIEW_RETRY : "intent, written before the call"
    REVIEW_RETRY ||--|| REVIEW_RESULT : "the provider's answer"
    TX_NEEDS_REVIEW ||--o| REVIEW_RESOLVED : "closes it"
    REVIEW_CHECKED { string by "who" list answers "op, key, answer" }
    REVIEW_RETRY { string by string op "settle, cancel_accept, void_authorisation, refund" string key }
    REVIEW_RESULT { string by string op string key string result }
    REVIEW_RESOLVED { string by string outcome "paid, not_paid, written_off" string note "why" }
    BULK_STARTED ||--o| BULK_FINISHED : "ends"
    BULK_STARTED { json plan "count, min and max in minor units, interval, mode, seed, fixed settings, randomised settings, sender cap" }
    BULK_FINISHED { string status "finished, stopped" int submitted int completed int failed int needs_review int throttled int elapsed_ms }
```

`Replay.Tx.summarize` gains `resolution` (the last resolution's outcome, or none); `Replay.batches/1` reads the batch records; the review queue
is rebuilt with `Review.Queue.from_records/1`. The request carries `source_currency` and `destination_currency` when given.


# 03 · System sequence diagrams (Larman style)

The system is a single black box (`:System`). Only the actors' requests and the
system's responses are shown; internals are in [04](04-design-sequences-and-activities.md).

## SSD-1 · Complete an FX swap and payment (UC-1)

```mermaid
sequenceDiagram
    autonumber
    actor M as Merchant system
    participant S as ":System"

    M->>S: submit(amount, [id])
    S-->>M: {:ok, transactionId}
    Note over S: System quotes, selects, accepts,<br/>authorises and pays asynchronously

    M->>S: await(transactionId, timeout)
    alt completed
        S-->>M: {:ok, summary: provider, rate, fee, net, steps}
    else failed (no quote / all candidates exhausted)
        S-->>M: {:error, reason}
    else needs review (in doubt or compensation failed)
        S-->>M: {:error, {:needs_review, reason}}
    else caller timeout
        S-->>M: {:error, :timeout}
    end

    opt progress while waiting
        M->>S: snapshot(transactionId)
        S-->>M: phase, provider, done steps, history
    end
```

## SSD-2 · Observe and operate (UC-2, UC-5)

```mermaid
sequenceDiagram
    autonumber
    actor O as Operator
    participant S as ":System (dashboard)"

    O->>S: GET /
    S-->>O: dashboard page
    O->>S: GET /events (server-sent events)
    S-->>O: replay of recent narrative
    loop while connected
        S-->>O: event (actor, kind, message, data)
    end

    O->>S: GET /api/snapshot
    S-->>O: providers, policy, traffic mode

    O->>S: POST /api/control {submit | auto | pace | policy | provider ...}
    alt accepted
        S-->>O: {ok: true, result}
    else invalid (e.g. bad policy, unknown provider)
        S-->>O: 422 {ok: false, error}
    end
```

## SSD-3 · Keep provider knowledge honest (UC-3)

```mermaid
sequenceDiagram
    autonumber
    actor T as Sentinel timer
    participant S as ":System"
    actor P as Provider

    loop every probe interval
        T->>S: probe all providers
        S->>P: probe()
        P-->>S: version, latency | error | timeout
        S-->>T: health updated (healthy / degraded / stalled / quarantined)
    end
```

## SSD-4 · Audit a transaction and prove the record is intact (UC-7)

```mermaid
sequenceDiagram
    autonumber
    actor A as Auditor
    participant S as ":System"

    A->>S: verify(audit log path)
    alt chain intact
        S-->>A: OK: N records, head hash
        A->>A: compare head with the independently anchored value
    else a record was altered, removed or reordered
        S-->>A: NOT intact: line L, reason
    end
    A->>S: read records for transaction X
    S-->>A: ordered, time-stamped events from tx_received to outcome, incl. measured cost
```

## SSD-5 · Read what the system costs and saves (UC-8)

```mermaid
sequenceDiagram
    autonumber
    actor O as Operator
    participant S as ":System"

    O->>S: GET /api/snapshot
    S-->>O: economics: totals and per provider (value in minor units, cost in pico-units)
    Note over O,S: each finished transaction also appears in the narrative as a "tx_costed" event
```

## SSD-6 · Replay the day (UC-9)

```mermaid
sequenceDiagram
    autonumber
    actor U as Operator / auditor
    participant S as ":System (replay)"

    U->>S: load(log)
    alt chain broken
        S-->>U: refused: line L, reason
    else intact
        S-->>U: day loaded (records, transactions)
    end
    U->>S: state_at(record n | seq | time)
    S-->>U: transactions in flight and finished, provider health and prices, policy, operator actions
    U->>S: verify()
    S-->>U: all rules held | violations (rule, transaction, audit record)
    U->>S: what_if(policy changes)
    S-->>U: routes that change, net and latency effect
    U->>S: diff(other system's log)
    S-->>U: identical count, divergences by transaction and field
```

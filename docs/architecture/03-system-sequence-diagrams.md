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

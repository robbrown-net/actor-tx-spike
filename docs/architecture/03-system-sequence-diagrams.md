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

## SSD-7 · Restart and resume (UC-10)

```mermaid
sequenceDiagram
    autonumber
    actor O as Operator
    participant S as ":System"

    O->>S: start (logs from the previous run)
    S->>S: verify and load every log
    alt a log fails verification
        S-->>O: refuses to start, naming the line
    else all intact
        S-->>O: open transactions found, and where each stood
        loop each open transaction
            S->>S: resume (quote again | next step | ask the provider | finish undos | record completion)
        end
        S-->>O: resumed, or escalated to a person
    end
```

## SSD-9 · Test the hard-to-reach behaviours (UC-12)

```mermaid
sequenceDiagram
    autonumber
    actor T as Tester
    participant S as ":System with the Test lab"

    T->>S: run scenario (or all 17)
    S-->>T: pass / fail per check, with evidence
    opt build a situation by hand
        T->>S: set audit log healthy / down / fails after N steps
        T->>S: arm a crash at an event (kind, step)
        T->>S: submit transactions (same id twice if testing idempotency)
        S-->>T: what each transaction did, and what the providers really did
        T->>S: plan recovery (dry run)
        S-->>T: for each open transaction: where the log says it stood
        T->>S: recover now
        S-->>T: resumed, or escalated to a person
        T->>S: kill the audit router
        S-->>T: money refused while it is down, then restarted by the supervisor
        T->>S: verify logs and residency
        S-->>T: chains intact, rules held, residency held (or what broke)
    end
```

## SSD-10 · Send many transactions (UC-13)

```mermaid
sequenceDiagram
    autonumber
    actor O as Operator
    participant S as ":System"

    O->>S: start batch (count, min and max amount, pause in ms, mode, seed, settings)
    alt plan invalid or a batch already running
        S-->>O: refused, with every reason
    else
        S-->>O: started
        loop one submission per pause until count is reached
            S->>S: draw amount (and settings in random mode), submit
            S-->>O: progress (sent, in flight, completed, failed, needs review, rate)
        end
        S-->>O: finished once every transaction has ended
    end
    opt stop early
        O->>S: stop
        S-->>O: stopped (already sent transactions finish and are counted)
    end
```

## SSD-8 · Submit within a jurisdiction (UC-11)

```mermaid
sequenceDiagram
    autonumber
    actor M as Merchant system
    participant S as ":System"

    M->>S: submit(amount, origin, destination, instrument, customer type)
    S->>S: decide eligibility (strictest region wins)
    alt instrument or customer type not permitted
        S-->>M: failed: not permitted, with reasons (no provider asked)
    else no provider eligible
        S-->>M: failed: no eligible provider
    else
        S->>S: quote only eligible providers, then proceed
        S->>S: record every event in the logs the decision names
        S-->>M: completed or escalated
    end
```

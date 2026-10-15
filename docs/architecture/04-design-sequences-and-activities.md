# 04 · Design sequences, activities and state machines

Design-level views of how the system realises the use cases. Diagrams are Mermaid
(UML 2.0 notation as closely as Mermaid allows: sequence combined fragments
`alt/opt/loop/par`, state machines, and activity flows with swimlanes).

## 4.1 Happy path (UC-1 main scenario)

```mermaid
sequenceDiagram
    autonumber
    actor M as Merchant
    participant API as ActorTxSpike
    participant P as Payment (per tx)
    participant SEL as Selector
    participant PS as Provider.Server (xN)
    participant AD as Adapter (provider)
    participant EV as Events

    M->>API: submit(request, providers, policy)
    API->>P: start under TransactionSupervisor
    API-->>M: {:ok, id}
    P->>EV: tx_received
    P->>SEL: select(request, providers, policy)
    SEL->>EV: select_started
    par each callable provider
        SEL->>PS: request_quote(request, deadline)
        PS->>AD: request_quote (in caller-owned task, timeout enforced)
        AD->>EV: received / processing / responded
        AD-->>PS: {:ok, quote}
        PS-->>SEL: quote
        PS->>EV: health sample
    end
    SEL->>SEL: constraints, max_bps_from_best, scoring, ranking
    SEL->>EV: selection_made
    SEL-->>P: Selection (ranked candidates)
    P->>PS: accept_quote(quote, key "tx:prov:accept")
    PS->>AD: accept_quote
    AD-->>P: {:ok, receipt}
    P->>PS: authorise(quote, key "tx:prov:authorise")
    AD-->>P: {:ok, receipt}
    P->>PS: pay(quote, key "tx:prov:pay")
    AD-->>P: {:ok, receipt}
    P->>EV: tx_completed
    M->>P: await(id)
    P-->>M: {:ok, summary}
```

## 4.2 Authorise rejected: compensate and fail over (UC-1 ext 7a)

```mermaid
sequenceDiagram
    autonumber
    participant P as Payment
    participant A as Provider A (best)
    participant B as Provider B (next)
    participant EV as Events

    P->>A: accept_quote(key a:accept)
    A-->>P: ok
    P->>A: authorise(key a:authorise)
    A-->>P: rejected (declined)
    P->>EV: step_failed
    P->>A: cancel_accept(key a:accept)
    A-->>P: ok
    P->>EV: undone, failover
    Note over P: quote of B still valid?
    P->>B: accept_quote(key b:accept)
    B-->>P: ok
    P->>B: authorise(key b:authorise)
    B-->>P: ok
    P->>B: pay(key b:pay)
    B-->>P: ok
    P->>EV: tx_completed (via B)
```

## 4.3 Payment answer lost: reconcile, never fail over (UC-1 ext 8b)

```mermaid
sequenceDiagram
    autonumber
    participant P as Payment
    participant A as Provider A
    participant EV as Events

    P->>A: pay(key a:pay)
    Note over A: effect may or may not happen
    A--xP: timeout / answer lost
    P->>EV: in_doubt
    loop up to reconcile_attempts
        P->>A: status(pay, key a:pay)
        alt provider holds the payment
            A-->>P: {:ok, receipt}
            P->>EV: reconciled, tx_completed
        else not found / unknown / timeout
            A-->>P: not_found
            P->>EV: reconcile_pending
        end
    end
    opt still unresolved
        P->>EV: needs_review
        Note over P: NO fail over, NO refund: a human decides
    end
```

## 4.4 Sentinel detects drift (UC-3)

```mermaid
sequenceDiagram
    autonumber
    participant T as Sentinel
    participant PS as Provider.Server
    participant AD as Adapter
    participant SEL as Selector
    participant EV as Events

    T->>PS: probe
    PS->>AD: probe
    AD-->>PS: version v2 (was v1)
    PS->>PS: observe_version, quarantine
    PS->>EV: health: healthy -> quarantined (version changed v1 -> v2)
    SEL->>PS: state?
    PS-->>SEL: quarantined
    SEL->>EV: provider_skipped
    Note over SEL: not called until released
```

## 4.5 Activity: one transaction step (swimlane view)

```mermaid
flowchart TD
    subgraph Payment["Payment (orchestrator)"]
        s0(["start step: accept / authorise / pay"]) --> s1["call provider with idempotency key"]
        s4{"outcome?"}
        s5["next step, or complete"]
        s6{"attempts left?"}
        s7["retry same key after backoff"]
        s8["compensate completed steps (reverse order)"]
        s9{"more candidates?"}
        s10(["fail over to next candidate"])
        s11(["failed: exhausted"])
        s12["ask provider: status(op, key)"]
        s13{"held at provider?"}
        s14{"reversible step?"}
        s15["cancel by key, then compensate"]
        s16{"reconcile attempts left?"}
        s17(["needs_review: no fail over"])
        s18(["completed"])
        s19["step done"]
    end
    subgraph Provider["Provider.Server and adapter"]
        s2["breaker admits call? enforce timeout"]
        s3["perform effect or answer"]
    end

    s1 --> s2 --> s3 --> s4
    s4 -- "ok" --> s19 --> s5
    s5 -- "last step" --> s18
    s4 -- "technical error" --> s6
    s6 -- "yes" --> s7 --> s1
    s6 -- "no" --> s8
    s4 -- "rejected" --> s8
    s8 --> s9
    s9 -- "yes" --> s10
    s9 -- "no" --> s11
    s4 -- "timeout or in doubt" --> s12 --> s13
    s13 -- "yes" --> s19
    s13 -- "no" --> s14
    s14 -- "yes: accept, authorise" --> s15 --> s8
    s14 -- "no: pay" --> s16
    s16 -- "yes" --> s12
    s16 -- "no" --> s17
```

## 4.6 State machine: Payment

```mermaid
stateDiagram-v2
    [*] --> received
    received --> quoting
    quoting --> accepting : candidate chosen
    quoting --> failed : no usable quote
    accepting --> authorising : accepted
    authorising --> paying : authorised
    paying --> completed : paid
    accepting --> reconciling : timeout / answer lost
    authorising --> reconciling : timeout / answer lost
    paying --> reconciling : timeout / answer lost
    reconciling --> authorising : accept confirmed
    reconciling --> paying : authorise confirmed
    reconciling --> completed : pay confirmed
    reconciling --> compensating : reversible step unconfirmed
    reconciling --> needs_review : pay unresolved
    accepting --> compensating : rejected / retries exhausted
    authorising --> compensating : rejected / retries exhausted
    paying --> compensating : rejected / retries exhausted
    compensating --> accepting : failover, next candidate
    compensating --> failed : candidates exhausted
    compensating --> needs_review : undo failed
    completed --> [*]
    failed --> [*]
    needs_review --> [*]
```

## 4.7 State machine: provider health and breaker

```mermaid
stateDiagram-v2
    [*] --> unknown
    unknown --> healthy : first good calls
    healthy --> degraded : error rate high or p95 slow
    degraded --> healthy : errors age out
    healthy --> stalled : consecutive failures reach threshold (breaker open)
    degraded --> stalled : consecutive failures reach threshold
    stalled --> probing : cool-down elapsed (half-open, one call)
    probing --> stalled : probe fails
    probing --> recovering : probe succeeds, history reset
    recovering --> recovering : success (streak builds)
    recovering --> stalled : failures reach threshold
    recovering --> healthy : recovery_successes reached
    healthy --> quarantined : version changed
    degraded --> quarantined : version changed
    recovering --> quarantined : version changed
    quarantined --> healthy : released
```

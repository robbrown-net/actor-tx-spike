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
    participant NAR as Selector.Narrator
    participant PS as Provider.Server (xN)
    participant AD as Adapter (provider)
    participant EV as Events

    M->>API: submit(request, providers, policy)
    API->>P: start under TransactionSupervisor
    API-->>M: {:ok, id}
    P->>EV: tx_received (and the journal copy)
    Note over P: admission: eligibility decided first<br/>(not permitted or no eligible provider: fail here, no provider asked)
    P->>SEL: select(request, providers, policy)
    SEL->>NAR: started
    NAR->>EV: select_started
    par each callable provider
        SEL->>PS: request_quote(request, deadline)
        PS->>AD: request_quote (in caller-owned task, timeout enforced)
        AD->>EV: received / processing / responded
        AD-->>PS: {:ok, quote}
        PS-->>SEL: quote
        PS->>EV: health sample
    end
    SEL->>NAR: observation (journal of each answer)
    SEL->>SEL: evaluate/3 (pure): constraints, max_bps_from_best, scoring, ranking
    SEL->>NAR: item per provider, then outcome
    NAR->>EV: quote_received / quote_excluded / selection_made
    SEL-->>P: Selection (ranked candidates)
    P->>PS: accept_quote(quote, key "tx:prov:accept")
    PS->>AD: accept_quote
    AD-->>P: {:ok, receipt}
    P->>PS: authorise(quote, key "tx:prov:authorise")
    AD-->>P: {:ok, receipt}
    P->>PS: pay(quote, key "tx:prov:pay")
    AD-->>P: {:ok, receipt}
    P->>PS: settle(key "tx:prov:settle")
    AD-->>P: {:ok, receipt}
    P->>EV: guarantee margin (if any), tx_completed
    M->>P: await(id)
    P-->>M: {:ok, summary}
```

### 4.1b Inside a selection: decide, observe, narrate

`Selector.select/4` is a pipeline of small steps; the only judgement is the pure `evaluate/3`, which
replay calls with the recorded answers. Everything people read or replay needs is said by `Selector.Narrator`.

```mermaid
flowchart LR
    A["partition providers by health state<br/>(excluded states are not called)"] --> B["observe: ask the rest concurrently,<br/>within the deadline, stamp the time"]
    B --> C["Narrator.observation<br/>(journal: raw answer, latency, state)"]
    B --> D["evaluate/3 (pure)<br/>judge, within_best, rank"]
    D --> E["Narrator.item per provider"]
    D --> F["build Selection<br/>(selected / fallback / none)"]
    F --> G["Narrator.outcome"]
    D -. same code .-> R["Replay.Tx.reselect"]
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

## 4.4b Deadline: cut-off passes mid-transaction

```mermaid
sequenceDiagram
    autonumber
    participant P as Payment
    participant A as Provider A
    participant EV as Events

    P->>EV: deadline_set (UK cut-off in 90 s)
    P->>A: accept_quote
    A-->>P: ok
    Note over P: cut-off passes
    P->>EV: deadline_missed
    P->>A: cancel_accept (undo)
    A-->>P: ok
    P->>EV: tx_failed {deadline_missed, UK}
    Note over P: after pay but before settle:<br/>needs_review {settlement_overdue}, never undone
```

## 4.4c Measurement, pricing and evidence at the end of a transaction

```mermaid
sequenceDiagram
    autonumber
    participant PAY as Payment
    participant EV as Events
    participant ECO as Economics
    participant AUD as Audit
    participant DASH as Dashboard

    PAY->>EV: tx_completed (terminal note)
    PAY->>EV: tx_economics {outcome, usage, price discovery, margin, provider fee}
    EV-->>ECO: tx_economics
    ECO->>ECO: price usage with the rate card (integer pico-units)
    ECO->>ECO: add to totals, overall and per provider
    ECO->>EV: tx_costed {cost, value}
    EV-->>AUD: every event, in order
    AUD->>AUD: scrub, hash with previous, append, datasync
    EV-->>DASH: stream (narrative) and snapshot (economics panel)
```

`Payment` only measures. It never prices, so the rate card can change without touching the
orchestrator, and the raw facts stay in the audit log so any cost figure can be recomputed.
Observation is fire-and-forget: if `Economics` or `Audit` is slow, payments are unaffected.
(The consequence for compliance is that a dead audit writer goes unnoticed: see the
compliance map, gap 1.)

### Audit log record lifecycle

```mermaid
stateDiagram-v2
    [*] --> Verifying : start
    Verifying --> Refused : any record fails verification
    Verifying --> Appending : chain intact (or no file)
    Appending --> Appending : event arrives, scrub, hash, write, sync
    Appending --> [*] : stopped
    Refused --> [*]
```

## 4.4d Recording and replaying the day

```mermaid
sequenceDiagram
    autonumber
    participant W as Outside world (merchant, providers, operator)
    participant SEL as Selector
    participant PAY as Payment
    participant J as Journal and Events
    participant AUD as Audit log (hash chain)
    participant REP as Replay

    W->>PAY: request
    PAY->>J: tx_received {request, policy, guarantee, config, providers}
    PAY->>SEL: select
    SEL->>W: quote requests
    W-->>SEL: answers (with latency)
    SEL->>J: quote_observed per provider {outcome, rate, fee, expiry, state, latency, seen at}
    SEL->>SEL: evaluate (pure)
    SEL->>J: selection_made {ranking}
    loop each provider call
        PAY->>W: step with idempotency key
        W-->>PAY: answer
        PAY->>J: provider_response {key, attempt, class, latency}
    end
    J->>AUD: every event, scrubbed, chained
    Note over AUD,REP: later, from the log alone
    REP->>AUD: load (verifies the chain)
    REP->>REP: Selector.evaluate over recorded observations = the same decision code, replayed
    REP->>REP: rules, state_at, what_if, diff
```

The pure `evaluate` function is the seam: live, it is fed what providers just said; in replay,
what the log says they said. See ADR 0009 for what this can and cannot replay.

## 4.4e Write-ahead, fail closed and regional routing

```mermaid
sequenceDiagram
    autonumber
    participant PAY as Payment
    participant EV as Events
    participant RT as Audit.Router (durable sink)
    participant LOG as Regional logs (IN, CN, GLOBAL)
    participant PRV as Provider

    PAY->>EV: step_started (op, key)
    EV-->>RT: event
    RT->>LOG: write to the logs this transaction's decision names
    PAY->>EV: barrier
    EV-->>PAY: sinks to flush
    PAY->>RT: flush
    RT->>LOG: make durable (one fsync covers everything written)
    RT-->>PAY: ok
    alt barrier ok
        PAY->>PRV: the call (idempotency key)
    else sink down or unresponsive
        PAY->>PAY: do not call, so failed (nothing done) or needs_review (money moved)
    end
```

### Recovery decision

```mermaid
flowchart TD
    A["Open transaction in the log"] --> B{"Provider chosen?"}
    B -- no --> F["fresh: quote again"]
    B -- yes --> C{"Quote for it recorded?"}
    C -- no --> R["review: a person decides"]
    C -- yes --> D{"Compensation under way?"}
    D -- yes --> U["undo: finish the remaining undos, then fail over or abort"]
    D -- no --> E{"Last thing recorded"}
    E -- "step succeeded" --> G{"Was it settle?"}
    G -- yes --> K["complete: record the completion"]
    G -- no --> N["next: send the step that was never sent"]
    E -- "retry scheduled" --> N
    E -- "step sent, no answer / status check" --> Q["doubt: ask the provider what it holds"]
    Q --> Q2{"Provider's answer"}
    Q2 -- "confirms" --> N
    Q2 -- "reversible step unconfirmed" --> U
    Q2 -- "pay or settle unresolved" --> R
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
    paying --> settling : paid
    settling --> completed : settled
    settling --> needs_review : refused / overdue / unresolved
    settling --> reconciling : timeout / answer lost
    reconciling --> completed : settle confirmed
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

# 05 · System structure

## 5.1 Components

```mermaid
flowchart LR
    subgraph Ingress
        API["ActorTxSpike (public API)"]
        PANEL["Panel (HTTP + SSE, loopback)"]
        MIX["mix demo"]
    end
    subgraph Core["Transaction core"]
        TS["TransactionSupervisor (DynamicSupervisor)"]
        PAY["Payment (1 process per transaction)"]
        SEL["Selector (fan-out quoting)"]
        POL["Policy (validated data)"]
        PSET["PolicySet (global, merchant, customer cascade)"]
        CUT["Cutoff (regional deadlines)"]
        GUA["Guarantee and Ledger"]
        MON["Money (exact integer arithmetic)"]
    end
    subgraph ProviderLayer["Provider layer"]
        PSRV["Provider.Server xN (breaker, health, bulkhead, timeouts)"]
        HEALTH["Provider.Health (pure model)"]
        ADAPT["Provider behaviour (adapters)"]
        FAKE["Provider.Fake (scriptable)"]
    end
    subgraph Observability
        EV["Events (ring buffer + pub/sub)"]
        MET["Metrics (sampler, rollups)"]
        NARR["Narrative (terminal)"]
        DASH["priv/panel.html (dashboard)"]
    end
    subgraph Evidence["Evidence and economics"]
        ECO["Economics (prices usage, keeps totals)"]
        COST["Costs (rate card, pico-units)"]
        AUD["Audit (hash-chained log)"]
        FILE[("audit log file")]
    end
    DEMO["Demo world (providers, traffic, sentinel, operator controls)"]

    API --> TS --> PAY
    PAY --> SEL
    SEL --> POL
    API --> PSET
    PAY --> CUT
    PAY --> GUA
    EV --> MET
    PAY -. measures .-> EV
    EV --> ECO
    ECO --> COST
    ECO -. tx_costed .-> EV
    EV --> AUD --> FILE
    ECO --> PANEL
    PAY --> MON
    SEL --> MON
    GUA --> MON
    MET --> PANEL
    SEL --> PSRV
    PAY --> PSRV
    PSRV --> HEALTH
    PSRV --> ADAPT
    FAKE -. implements .-> ADAPT
    PAY -. emits .-> EV
    SEL -. emits .-> EV
    PSRV -. emits .-> EV
    FAKE -. emits .-> EV
    EV --> NARR
    EV --> PANEL --> DASH
    MIX --> DEMO
    DEMO --> API
    DEMO --> FAKE
    PANEL --> DEMO
```

## 5.2 Supervision and process ownership

```mermaid
flowchart TD
    APP["ActorTxSpike.Supervisor (one_for_one)"]
    APP --> EVS["Events"]
    APP --> MET["Metrics"]
    APP --> ECON["Economics"]
    APP --> LED["Ledger"]
    APP --> REG["Registry (payment ids)"]
    APP --> TSUP["TransactionSupervisor"]
    TSUP --> P1["Payment tx-1 (temporary)"]
    TSUP --> P2["Payment tx-2 (temporary)"]
    P1 -. owns .-> T1["Task: current provider call"]

    DEMO["Demo (started by mix task)"]
    DEMO -. links .-> PS1["Provider.Server northwind"]
    DEMO -. links .-> PS2["Provider.Server bluebird"]
    DEMO -. links .-> F1["Provider.Fake agents"]
    MT["mix demo process"] -. links .-> PANEL["Panel + acceptor + connection processes"]
    MT -. "links (only with --audit)" .-> AUDIT["Audit"]
```

> **Known gaps:** Provider servers, the panel and the **audit writer** are *not* under the
> application's supervision tree today; they are linked to the demo. A dead audit writer
> leaves the system running unlogged; it should fail closed. A production deployment needs
> them supervised (and Payment processes restarted from a durable log).

## 5.3 Module responsibilities

| Module | Responsibility | Pure? |
|---|---|---|
| `ActorTxSpike` | Public API: submit / await / snapshot | no |
| `Payment` | Lifecycle, retries, compensation, reconcile, escalation | no (process) |
| `Selector` | Concurrent quoting within deadline, ranking, narration | no |
| `Policy` | Validated routing policy, constraints, scoring | **yes** |
| `Provider` | Adapter behaviour and result vocabulary | n/a |
| `Provider.Health` | Breaker, rolling stats, drift quarantine, recovery | **yes** |
| `Provider.Server` | Admission, bulkhead, enforced timeouts, health events | no |
| `Provider.Fake` | Scriptable provider and its narration | no |
| `PolicySet` | Cascading objectives with entitlement and validation | **yes** |
| `Cutoff` | Regional cut-offs: fixed offset or date-aware offset function | **yes** |
| `Guarantee` | Margin / exposure against a standing guarantee | **yes** |
| `Ledger` | Accumulates guarantee outcomes per merchant and period | no |
| `Money` | Exact integer money: decimal parsing, half-even rounding, display formatting | **yes** |
| `Costs` | Rate card and pricing of measured usage into pico-units | **yes** |
| `Economics` | Prices each finished transaction, keeps totals overall and per provider, announces `tx_costed` | no (process) |
| `Audit` | Hash-chained append-only log, verification, card-number scrubbing | no (process) |
| `Metrics` (+ `Stats`, `Rollup`, `VM`) | Resource and application metrics with bounded rollups | mostly |
| `Events`, `Narrative` | Narrative stream and rendering | no |
| `Panel`, `Demo` | Dashboard server and demo world | no |

The decision-heavy logic (`Policy`, `Health`) is deliberately pure so it can be tested exhaustively.

## 5.4 Deployment view (today)

```mermaid
flowchart LR
    B["Browser (operator)"] -- "HTTP + SSE, 127.0.0.1:4040" --> N
    subgraph N["One BEAM node (OTP 27 / Elixir 1.18)"]
        A["application + demo world + panel"]
    end
    N -. "future: HTTPS / provider APIs" .-> P["Third-party providers"]
    N -- "append, datasync" --> L[("audit log file (hash chain)")]
    N -. "future: durable store" .-> D[("Database")]
    L -. "anchor head, off-host copy (not built)" .-> X["Independent system"]
```

Single node. Nothing is persisted for recovery; the audit log is evidence, not a recovery store. No external network calls (providers are fakes).

## 5.5 Dependencies

* **Runtime:** none beyond OTP/Elixir standard library (JSON module from Elixir 1.18).
* **Dev/test only:** credo, sobelow, mix_audit (and their transitive deps).
* **Tooling entry points:** `mix demo`, `mix audit.verify`.

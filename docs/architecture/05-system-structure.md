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
        SEL["Selector (fan-out quoting, pure evaluate/3)"]
        NAR["Selector.Narrator (events and journal of a selection)"]
        POL["Policy (validated data)"]
        PSET["PolicySet (global, merchant, customer cascade)"]
        CUT["Cutoff (regional deadlines)"]
        GUA["Guarantee and Ledger"]
        MON["Money (exact integer arithmetic)"]
        VAL["Validation (shared field parsing and checks)"]
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
        JRN["Journal (replay inputs, hidden from humans)"]
        REP["Replay (state_at, Rules, WhatIf, Compare, Residency)"]
        RTR["Audit.Router (one log per region)"]
        LCK["Audit.Lock (one writer)"]
        REC["Recovery (plan, recover)"]
        ELIG["Eligibility (jurisdiction rules)"]
        EPAR["Eligibility.Parser (rule data to Rule structs)"]
    end
    DEMO["Demo world (providers, traffic, sentinel, operator controls)"]
    LAB["Demo.Lab (Design for Test: faults, crash, scenarios)"]
    BULK["Demo.Bulk (sends batches of transactions)"]
    BPLAN["Demo.Bulk.Plan (pure: validate, draw amounts and settings)"]
    POLL["Poll (wait for a condition; used by lab and tests)"]

    API --> TS --> PAY
    PAY --> SEL
    SEL --> POL
    SEL --> NAR
    NAR -. announces and journals .-> EV
    POL --> VAL
    PSET --> VAL
    CUT --> VAL
    GUA --> VAL
    ELIG --> EPAR --> VAL
    LAB --> DEMO
    BULK --> BPLAN
    BULK --> DEMO
    EV -. "outcomes counted" .-> BULK
    LAB --> POLL
    LAB --> REC
    API --> PSET
    PAY --> CUT
    PAY --> GUA
    EV --> MET
    PAY -. measures .-> EV
    EV --> ECO
    ECO --> COST
    ECO -. tx_costed .-> EV
    EV --> AUD --> FILE
    PAY -. inputs .-> JRN
    SEL -. inputs .-> JRN
    JRN --> EV
    EV --> RTR --> FILE
    AUD --> LCK
    PAY -. before scoring .-> ELIG
    PAY -. barrier before money moves .-> EV
    FILE --> REC --> PAY
    FILE --> REP
    REP -. same decision code .-> SEL
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
    APP --> RTR["Audit.Router (when audit is configured; permanent)"]
    RTR -. links .-> LOGS["one Audit per regional log"]
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

> **Known gaps:** Provider servers and the panel are *not* under the application's supervision
> tree today; they are linked to the demo. The audit router *is* supervised (when `audit` is
> configured): a dead writer makes the system fail closed, the supervisor restarts it, and
> production (`require_audit: true`) refuses to start without it. Open transactions are resumed
> from the log by `Recovery`, but recovery is run by the host at start (as `mix demo` does), not
> automatically by the application, because it needs the live provider registry.

## 5.3 Module responsibilities

| Module | Responsibility | Pure? |
|---|---|---|
| `ActorTxSpike` | Public API: submit / await / snapshot | no |
| `Payment` | Lifecycle, retries, compensation, reconcile, escalation | no (process) |
| `Selector` | Concurrent quoting within deadline; `evaluate/3` is the pure judgement shared with replay | `evaluate/3`: **yes** |
| `Selector.Narrator` | The story of a selection: events people read and the journal records replay reads | no |
| `Validation` | The shared shape of "plain data to validated struct": parse each field, collect every error | **yes** |
| `Poll` | Wait for a condition by asking again (one place that sleeps-and-retries) | no |
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
| `Journal` | Records replay inputs (actor `:journal`, hidden from humans) | no |
| `Eligibility` (+ `Rule`, `Parser`) | Jurisdiction rules: eligible providers, permitted instruments and customer types, logs, retention; strictest region wins | **yes** |
| `Audit.Router` | One hash-chained log per region; routes each transaction's events to the logs its decision names; fails closed | no (process) |
| `Audit.Lock` | One writer per log (lock file naming the owner; stale locks taken over) | no |
| `Recovery` | Pure plan of where an open transaction stood; recovery of every open transaction from the logs | plan: **yes** |
| `Replay` (+ `Tx`, `Rules`, `WhatIf`, `Compare`, `Residency`, `Report`) | Load a verified day; state at a moment; rule verification; strategy what-if; comparison with another system; text reports | **yes** |
| `Metrics` (+ `Stats`, `Rollup`, `VM`) | Resource and application metrics with bounded rollups | mostly |
| `Events`, `Narrative` | Narrative stream and rendering | no |
| `Panel`, `Demo` | Dashboard server (`/api/snapshot`, `/metrics`, `/lab`, `/bulk`, `/events`, `/control`) and demo world | no |
| `Demo.Bulk` | Sends a batch of transactions through the demo at a set pace and counts how each ends (see [09](09-bulk-send.md)) | no |
| `Demo.Bulk.Plan` | Validates a batch plan; draws each transaction's amount and (in random mode) settings from a seeded random state | **yes** |
| `Demo.Lab` (+ `Scenarios`) | Design for Test: audit fault injection, crash watcher, plan/recover/resend, 17 scenarios with checks (see [08](08-test-lab.md)) | no |

The decision-heavy logic (`Policy`, `Health`, `Selector.evaluate/3`, `Eligibility`, `Recovery.plan/1`) is deliberately pure so it can be tested exhaustively.

### Shape rules the structure follows

These are enforced by Credo (see the [quality limits](../DEVELOPER_GUIDE.md#3-quality-limits-and-how-they-are-enforced)): a function has at most **4** decision points (cyclomatic complexity), at most **40** ABC size, and no repeated block of about 16 nodes. In practice that is why the structure looks like this: decisions live in function clauses and small named helpers (`Validation`, `Parser`, `Narrator`), not in nested `if`/`case`, and one concern gets one module (deciding, narrating and parsing are separate).

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

Single node. The audit log is now also the recovery store: open transactions resume from it after a restart. Deals that live for days (forwards) are not yet modelled. No external network calls (providers are fakes).

## 5.5 Dependencies

* **Runtime:** none beyond OTP/Elixir standard library (JSON module from Elixir 1.18).
* **Dev/test only:** credo, sobelow, mix_audit (and their transitive deps).
* **Tooling entry points:** `mix demo` (`--audit`, `--audit-dir`; resumes open transactions at start), `mix audit.verify`, `mix replay` (including `residency`).

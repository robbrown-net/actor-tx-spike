# The engineering story

How `actor-tx-spike` went from a three-test Elixir experiment to an observable,
policy-driven FX payment orchestrator, and why. Written for someone joining the
project: it explains the *reasons*, not just the code.

* Where we started
* The questions that shaped everything
* Pivotal decisions
* How the design works now
* Engineering steers (how we work)
* Architecture steers (what the owner pushed on)
* Open questions
* Risks
* Roadmap
* Reading order

---

## 1. Where we started

**The idea.** Rob wanted to learn Elixir, the BEAM and OTP by exploring *stateful
transaction messages passed around stateful sub-systems*, in the spirit of Fred Brooks:
pick the most productive high-level language that fits, but keep an escape route
(Rust) if performance demands it.

**The first spike** (built in Cursor):

* One `gen_statem` per transaction: `queued → awaiting_a → awaiting_b → committed`, with `compensating → failed`.
* Two fake subsystem `GenServer`s under a supervisor, idempotent per `tx_id`, with a "crash next request" switch.
* A disk-backed snapshot store so a node restart could resume in-flight work.
* Three tests. **76.5% line coverage.**

It worked, and it proved the OTP shape: a process per transaction, supervised workers,
restart on crash. But the analysis found 12 gaps worth recording because they set
the agenda: idempotency lived only in memory; resubmitting after restart re-executed;
finished processes never exited; `await` timeouts crashed the caller; compensation was
a stub; the store rewrote everything per write, non-atomically; one corrupt snapshot
could stop boot; A and B were hardcoded; the README claimed a vendored toolchain that
the repository did not contain.

## 2. The questions that shaped everything

### "Orchestrator or choreography?"

The owner asked us to settle this first, by clarifying the *business need*. The answers
decided it:

| Answer | Implication |
|---|---|
| Money, inventory, legal records | Half-done is unacceptable; audit trail and compensation are mandatory |
| Flow varies per transaction | Routing must be data, not code |
| Parallel fan-out and branching | Real workflow semantics |
| "Where is transaction X now?" | One place must know the whole story |
| Change flow without redeploying | The flow lives outside the participants |
| Scale across nodes | Participants addressed logically |

→ **Orchestration**, with flows as declarative data. (ADR 0001.)

### "What do the sub-systems actually *are*?"

The pivotal reframing came next. The sub-systems are **facades over unreliable
third-party providers**: an FX swap and an idempotent payment. They may be fast and
cheap, slow, expensive, degraded, stalled, or change behaviour without telling us.
The system must weigh cost and speed, keep going when a provider fails, and eventually
be self-healing and real-time worldwide, watching CPU, memory, storage and network.

That turned "run A then B" into **provider selection under uncertainty**. The
architecture, the fakes, the health model and the scoring all follow from this.

## 3. Pivotal decisions

| # | Decision | Why | Where |
|---|---|---|---|
| D1 | Orchestrator, one process per transaction | Single owner, single story, safe recovery | ADR 0001 |
| D2 | Declarative data for flows and policy | Change behaviour without redeploying; validate once | ADR 0001, `Policy` |
| D3 | Availability-first "keep going" semantics | A valid answer always exists; improve on a baseline | ADR 0001 |
| D4 | Single node first | Prove the model before distribution | ADR 0001 |
| D5 | Gates: unit 100%, integration > 85% | Owner requirement; keeps refactors safe | ADR 0005 |
| D6 | Quote freely; every later step effectful and keyed | Money safety | ADR 0002 |
| D7 | Never fail over a doubtful payment | Double payment is the worst outcome | ADR 0002 |
| D8 | Compensation as real provider operations | Matches what real providers expose | ADR 0002 |
| D9 | Providers modelled, not trusted (breaker, drift quarantine) | They change silently | ADR 0003 |
| D10 | Gradual recovery after an outage | One lucky probe must not restore full traffic | ADR 0003 |
| D11 | Observability first-class; one narrative stream | "I want to see it, feel it" | ADR 0004 |
| D12 | `max_bps_from_best` as a hard constraint | Speed must not buy a bad price | `Policy` |
| D13 | Zero runtime dependencies, local toolchain | Homebrew unsupported; small audit surface | ADR 0006 |
| D14 | Remove the legacy spike code once replaced | Keep the codebase honest | commit `b1286f2` |

## 4. How the design works now

```text
submit ─► Payment (one process per transaction)
            │ quote  ─► Selector ─► Provider.Server ×N ─► adapter
            │ policy: weights, constraints, max bps from best
            ├─ accept ► authorise ► pay        (idempotency key per step)
            ├─ definite failure ─► undo, fail over to next candidate
            ├─ doubt ─► ask provider (status) ─► never guess
            └─ unresolvable payment ─► needs_review
```

* **Selection** (`Selector`, `Policy`): concurrent quotes within a deadline; stalled and
  quarantined providers are not called; constraints, then a relative cost/speed/reliability
  score; deterministic ranking; baseline fallback.
* **Provider layer** (`Provider.Server`, `Provider.Health`): breaker, rolling stats,
  bulkhead, enforced timeouts, version-drift quarantine, gradual recovery.
* **Lifecycle** (`Payment`): retries, compensation, failover, reconciliation, escalation.
* **Narrative** (`Events`, `Narrative`, `Panel`): every component announces itself; the
  dashboard and terminal are projections.

Detailed diagrams: [architecture/](architecture/).

## 5. Engineering steers (how we work)

These came from the owner and are standing rules.

| Steer | Practice |
|---|---|
| "I'm tinkering to learn" | Explain *why*; prefer clarity over cleverness; show the system working |
| No Homebrew on this Intel Mac | Vendored toolchain; never suggest `brew` |
| Keep it local until the harness exists, then push on request | Commit and push only when asked |
| 100% unit / > 85% integration coverage | Gates in `mix.exs`; tests first for new modules |
| Branch-level coverage "later" | Deferred, tracked in the roadmap |
| "Do the product depth" | Traced the burst failures, built admission control and priority bulkheads, measured per limit with `mix load` (ADR 0013, quality/LOAD_RESULTS.md) |
| "Add a screen for sending many payments with a min and max size, a randomiser and a pause; and a way to randomise every UI setting" | Bulk send tab (`Demo.Bulk`, pure `Bulk.Plan`); same limits and docs standard as everything else |
| "Lower complexity and duplication thresholds; nested ifs are better handled by design patterns, interfaces and abstractions; it matters for new people" | Cyclomatic complexity 4, nesting 2, ABC 40, duplicate mass 16 enforced by Credo in CI ([ADR 0012](adr/0012-code-shape-limits.md)); about 30 functions reshaped into clauses, small modules and tables |
| "It's a black box, I want to see and feel it" | Observability became a requirement, not a nicety |
| Prefer recommendations over option lists | Decisions are brought with a recommended default |
| Ask when it matters | Questions are batched; defaults are stated |

## 6. Architecture steers (what the owner pushed on)

* **Declarative data** over code for flows and policy.
* **"Keep going"**: the system always starts with a valid result and may only improve it; unavailable providers must be routed around.
* **Single node first**, but do not design distribution out.
* **Compensation as real operations** and **a hard price-distance constraint** in policy.
* **Cascading objectives**: global → merchant (paid privilege) → special customer, plus weekly standing guarantees.
* **Two-leg lifecycle**: quote, accept, authorise, pay, then settlement phases with regional cut-offs (e.g. 18:00 UK local).
* **A sentinel** that checks provider version, up-state and response time on a configurable cadence.
* **Gradual recovery** rather than snap-back.
* **"Enums are an anti-pattern for object-oriented design"** (2026-10-23, during the Java conversion). Asked where design change will come from and how often, the answer was: plan for more steps, per-corridor workflows, asynchronous settlement and new routing criteria. Result: steps, workflows, operations, phases, provider capabilities and routing criteria became values and objects, the duplicated outcome enums became one, and the remaining closed enums are to be reviewed again whenever the domain changes (ADR 0016). The enum that drove behaviour (`Operation`) had been written by the assistant; the owner's challenge, and the evidence (four parallel lookup maps and three business-rule conditionals in the engine) is what changed it.

## 7. What was built, in order

| Step | Outcome |
|---|---|
| Push the spike | Public repo; repo-local git identity |
| ADR and roadmap | Decisions recorded |
| Phase 0 | Unit / integration split, coverage gates |
| Phase 1 | Provider behaviour, scriptable fake, health model, breaker, server facade |
| Slice 1 | `Policy` + `Selector`: deadline-bound quote-and-select |
| Slice 2 | `Payment` lifecycle, undo operations, `max_bps`, `Events`, dashboard, demo world, fault-storm invariant test; legacy removed |
| Docs pass | Architecture suite, quality snapshot, this story |
| Quality gaps | System suite (real process over HTTP/SSE), regression pack, mutation testing, CI workflow; fixed a dead-provider crash the new tests exposed |
| Monitoring | `Metrics` sampler, rollups and a Performance view |
| Slice 3 | Cascading policy, standing guarantees and ledger, regional cut-offs, deadline enforcement, settlement step |
| Dashboard motion | Persistent-DOM rewrite and calm motion (from David's handover notes) |
| Money, evidence, economics | Integer money, hash-chained audit log, cost and savings ledger, PCI and Dodd-Frank evidence notes (ADR 0008) |
| Replay the day | Journal in the log; `Replay`: state at a moment, 11 rules, what-if, comparison (ADR 0009) |
| Recovery and jurisdiction | Write-ahead barrier, fail closed, one writer per log, recovery from the log (ADR 0010); eligibility, regional logs, residency proof for 11 jurisdictions (ADR 0011) |
| Supervised audit | Audit router under the application supervisor; production refuses to start without it |
| Design for Test lab | `mix demo --lab`: fault injection, crash at an exact event, recover, 17 scenarios (architecture/08) |
| Code-shape limits | Credo limits on complexity, nesting, size and duplication; refactor of ~30 functions; shared `Validation`, `Poll`, `Selector.Narrator`, `Eligibility.Parser` (ADR 0012) |
| Java port, chunk 1 | Java 21 / Spring Boot skeleton with the quality gates; money, policy, jurisdiction, provider model, concurrent selector, sentinel, operator control, REST API; four modules ported by smaller-model subagents in worktrees, reviewed and merged (ADR 0015) |
| Product depth | Currencies price, route and permit; the review workflow (UC-4); recorded batches (ADR 0014); re-execution replay deferred |
| Load and back-pressure | Admission control, bulkhead priority classes, waiting instead of failing, a backing-off sender, `mix load`; 30% to 99.8% completion on a 2,000 burst (ADR 0013, LOAD_RESULTS) |
| Bulk send | A tab that sends 1 to 10,000 transactions with random amounts, a settable pause, optional seed and a "randomise everything" mode; currencies carried on requests (architecture/09) |
| Documentation pass | Developer, testing, requirements and user guides; diagrams and quality snapshot brought up to date |
| Java port, chunk 2 | Pay over HTTP: event bus, the payment engine, hash-chained audit log and router with the write-ahead barrier, admission control; four more modules ported by smaller-model subagents in worktrees (event bus, audit, economics, guarantees and cut-offs), reviewed, merged and wired |
| Java port, chunk 3 | Replay, review workflow, bulk send and load runner, metrics, the existing dashboard served by Spring (four more subagent ports); the replay check found a journal key the Java side had written differently from the Elixir side |
| Extensible domain model | Steps, workflows, provider capabilities, asynchronous step completion, pluggable routing criteria, one shared outcome vocabulary, named event kinds and a `tx_received` schema version (ADR 0016); two subagents did the criteria and outcome refactors |
| Java run-time fixes | The dashboard drew nothing because the Java API lacked a provider field the page maps over; the Performance tab was rebuilt for JVM figures; a server started with `spring-boot:run` broke when a build replaced its classes, so `./java/run` runs a jar (architecture/13) |
| Documentation pass (Java) | Architecture 13, developer, testing, requirements and user guides, quality snapshot, roadmap and ADRs brought up to date for the Java build |

## 8. Open questions

| # | Question | Why it matters |
|---|---|---|
| Q1 | Do real providers truly support idempotency keys *and* undo-by-key? | ADR 0002 assumes yes; if not, the safety rules change |
| Q2 | How stale can `status` be, and is there any real-time signal? | Sets `reconcile` timing and `needs_review` volume |
| Q3 | How should guarantee margin and exposure be accounted for durably (periods, rollovers, reconciliation with finance)? | The in-memory ledger records margin and exposure per merchant and period; real accounting rules are unknown |
| Q4 | Where does merchant/customer policy come from and who may edit it? | Cascade needs an owner and an audit trail |
| Q5 | Which settlement *phases* exist per region, and what are their individual deadlines? | One `settle` step and one cut-off exist; real settlement is multi-phase |
| Q6 | ~~What is the money type and rounding policy?~~ | **Answered (ADR 0008):** integer minor units, rates scaled 10^8, half-even rounding |
| Q7 | What regulatory duties apply (KYC/AML, record retention, audit)? | **Partly answered 2026-10-17:** spot and forward, business and some retail, eleven jurisdictions (UK, EU, US, India, Mexico, Brazil, Turkey, Saudi Arabia, Japan, South Korea, China). Orientation matrix and design consequences in docs/compliance/COMPLIANCE_EVIDENCE.md; counsel must confirm per country |
| Q8 | How should provider quote and call costs be accounted (rate limits, fees to us)? | Hedging is not free |
| Q9 | What are the real latency/throughput targets? | Decides the performance work and when Rust might matter |
| Q11 | Which strategy changes will be tried against a real day, and what counts as "better" (net, latency, failure rate, exposure)? | Decides which metrics `WhatIf` must report and which inputs must be journalled |
| Q12 | Which of the closed enums will really stay closed (instrument, customer type, outcome, breaker)? | Each is a bet; wrong ones are costly once logs and tools depend on their names |
| Q10 | Who resolves `needs_review`, how fast, with what tools? | UC-4 is unbuilt |

## 9. Risks

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | ~~Floats used for money and rates~~ | resolved 2026-10-17 | | Integer money (ADR 0008) |
| R2 | No persistence: node restart loses in-flight transactions | certain | high | Event-sourced durable log (Phase 3) |
| R3 | Provider contract assumptions (keys, undo, status) are wrong | medium | high | Validate against real APIs early; adapters isolate it |
| R4 | Dashboard control API is unauthenticated | low (loopback) | high if exposed | Keep loopback; add auth/TLS before exposure |
| R5 | Fakes are friendlier than reality (no partial failures, schema drift, throttling) | high | medium | Contract tests and recorded-traffic replay |
| R6 | `needs_review` accumulates with no tooling | medium | medium | UC-4 workflow, alerts, ageing SLO |
| R7 | Event buffer is lossy and in-memory | certain | low now | Durable narrative with retention |
| R8 | Providers and panel not under the app supervision tree | certain | medium | Proper supervision when the demo becomes a deployment |
| R9 | Bulkhead rejections under burst (seen in the storm test) | medium | medium | Capacity planning, queueing policy |
| R10 | Line coverage overstates test strength | medium | medium | Branch and mutation testing |
| R11 | No CI; gates are manual | medium | medium | Add CI |
| R12 | Single node = single failure domain | certain | high in production | Distribution phase; single-owner-per-transaction design |
| R13 | `Payment.handle_result/3` is growing | medium | low | Refactor to a step table before slice 3 |
| R14 | Time zones, DST and cut-offs are error-prone; offset rules are supplied by the caller | medium | high | Back the offset function with a timezone database; property tests |
| R15 | Settlement failure leaves a made payment needing human action (no undo) | medium | high | UC-4 review tooling, alerting, ageing SLO |
| R16 | Guarantee exposure is only recorded, not limited | medium | medium | Exposure limits and guarantee-aware routing |
| R17 | Metrics and narrative are in memory: no history across restarts | certain | medium | Durable tier (Phase 3) |
| R18 | CI is not yet a required check; first run was green (2026-10-15) | low | low | Branch protection |
| R19 | ~~The audit writer is not supervised and the system can start without one~~ | resolved 2026-10-20 | | The router is supervised and permanent; `require_audit` (production) refuses to start without it; fail closed while it is down. Alerting on restarts is still to do |
| R20 | Audit chain cannot detect tail truncation by itself | medium | medium | Anchor the head externally on a schedule |
| R21 | Cost rate card holds placeholder prices; byte counts are a proxy; "saved" depends on the median baseline | certain | medium | Use invoice figures; real wire counts in adapters; show the baseline always |
| R23 | Replay is quote-level: what-if assumes the alternative provider would have completed | certain | medium | Re-execution engine with recorded answers and a virtual clock |
| R24 | A new decision rule added without its journal inputs makes the day unreplayable | medium | medium | Rule: every decision must be re-derivable from journal data; replay tests break a day one way at a time |
| R25 | A day's log is large (about 23 KB per transaction) | certain | medium | Compression, tiering and retention policy |
| R22 | Regulatory scope is now known in outline (spot and forward; 11 jurisdictions) but unverified by counsel, and licence-holder and book-versus-arranged questions are open | high | high | Counsel review per priority jurisdiction; answer the open questions in docs/compliance/COMPLIANCE_EVIDENCE.md |
| R26 | Forwards need a long-lived durable deal record. Open spot transactions now survive a restart (log-based recovery); deals that live for days are not modelled | certain | high | Durable store (Phase 3) before any forward product |
| R27 | Data residency: regional logs and a residency proof exist, but retention is not enforced, rules are unconfirmed by counsel, and a corridor touching a local-only region leaves the other side without a record | high | high | Retention and archival; counsel review; decide the corridor policy |
| R28 | Recovery reconciles a pay it cannot prove was never sent as in doubt, so a crash in that window costs a human review; resumed work loses cost attribution and the policy label | certain | low | Accept; improve with provider-side status guarantees |
| R29 | Two writers on one log would break the chain; guarded by a lock file that relies on the OS process table | low | high | Keep the lock; use a real distributed lock if the log moves off a local disk |
| R32 | ~~A burst sent transactions to `needs_review` and failed most of them~~ **Addressed 2026-10-22**: traced to no limit on transactions in flight, undo/settle competing with new quotes for provider slots, and giving up on a busy provider. Admission control, bulkhead priority classes and waiting now exist (ADR 0013). Remaining: the limit (100) is tuned to fake providers and measured without the audit log | medium | medium | Re-measure with the audit log on and against real providers; alert on sustained refusal |
| R34 | The review workflow has no four-eyes approval, assignment or deadlines, and a manual resolution does not adjust the economics ledger | medium | medium | Add approval and deadlines when real reviewers exist; book manual outcomes in the ledger |
| R35 | Currencies share one two-decimal model (JPY and KRW have no minor units) and the price list is fixed data | certain | low | Per-currency exponents and a rate feed before any real pricing |
| R33 | Admission refusals leave no record (by design) and recovery-resumed transactions bypass the limit | low | low | Record refusals as counters in the metrics; count resumed transactions when recovery is automatic |
| R36 | The dashboard page reads many JSON fields on every frame and has no automated test: a missing field stopped it drawing (seen 2026-10-23) | medium | high for the demo | A test pins the provider fields; add a headless-browser smoke test |
| R37 | Audit logs written by Java and by Elixir are not cross-verified (canonical JSON of floats and escapes could differ) | medium | high | Verify each side's logs with the other's verifier before relying on mixed logs |
| R38 | Closed enums (`Outcome`, `Breaker`, `HealthState`, `Instrument`, `CustomerType`, `BulkheadClass`, review status, `Mode.Kind`) are bets that the sets are closed | medium | medium | Review them whenever the domain changes (ROADMAP) |
| R39 | The review service lists the standard steps itself, and replay rebuilds only three routing weights: a new step or criterion is not fully reflected in review and replay | certain once used | medium | Derive both from the recorded workflow and policy (ROADMAP follow-ups) |
| R30 | A one-off cascade of failures from a failed global audit sink was seen twice in full `mix test` runs and never reproduced | low | medium | If it recurs keep the first failure's output; suspected shared global state between suites |
| R31 | `selection_test` "mixed providers" compares latencies a millisecond apart and can flip under heavy parallel load | low | low | Re-run alone; make the fake's latencies explicit when next touched |

## 10. Roadmap

Status as of 2026-10-20. See also [ROADMAP.md](ROADMAP.md).

| Phase | Content | Status |
|---|---|---|
| 0 | Harness, gates | ✅ done |
| 1 | Provider model, fake, health, breaker | ✅ done |
| 2.1 | Policy + quote-and-select | ✅ done |
| 2.2 | Lifecycle, undo, reconcile, narrative, dashboard | ✅ done |
| 2.3 | Cascading policy, standing guarantees, deadlines, regional cut-offs, settlement step | ✅ done (multi-phase settlement and timezone data remain) |
| 2.4 | Exact money, audit chain, economics, replay, write-ahead recovery, jurisdiction and regional logs, supervised audit, Test lab, code-shape limits | ✅ done (retention, forwards, sanctions/KYC remain) |
| 3 | Sentinel (configurable, persistent), event-sourced durable log, durable store, supervision of providers; performance metrics | 🟡 metrics, charts, log-based recovery done; durable deal store and supervision of providers 🔲 |
| 4 | Load / soak / chaos testing, system suite, regression pack, CI | 🟡 system suite, regression pack, mutation testing, CI workflow done; load testing 🔲 |
| 5 | Distribution: single owner per transaction across nodes, partition convergence, provider discovery | 🔲 |
| 6 | Production hardening: auth, TLS, money types, compliance, operations runbook | 🔲 |

Near-term: make CI a required check, Dialyzer, measure load with the audit log on and against real providers, retention and rotation, forward deal lifecycle, sanctions/KYC gates, counsel review of the jurisdiction matrix, UC-4 review tooling, timezone data.

## 11. Reading order for a newcomer

1. This document.
2. [architecture/01-use-cases.md](architecture/01-use-cases.md): what the system is for.
3. [architecture/04-design-sequences-and-activities.md](architecture/04-design-sequences-and-activities.md): how it behaves.
4. `lib/actor_tx_spike/payment.ex`, `selector.ex`, `provider/health.ex`: the three modules that matter.
5. Run `mix demo --lab` and break things (the [user guide](USER_GUIDE.md)).
6. [DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md) for the commands and the quality limits, [TESTING.md](TESTING.md) for the test layers, [REQUIREMENTS.md](REQUIREMENTS.md) for what the system must do and where each is verified.
7. [adr/](adr/) for the reasons; [quality/QUALITY_SNAPSHOT.md](quality/QUALITY_SNAPSHOT.md) for the evidence.

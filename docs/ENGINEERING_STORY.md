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

## 8. Open questions

| # | Question | Why it matters |
|---|---|---|
| Q1 | Do real providers truly support idempotency keys *and* undo-by-key? | ADR 0002 assumes yes; if not, the safety rules change |
| Q2 | How stale can `status` be, and is there any real-time signal? | Sets `reconcile` timing and `needs_review` volume |
| Q3 | How should guarantee margin and exposure be accounted for durably (periods, rollovers, reconciliation with finance)? | The in-memory ledger records margin and exposure per merchant and period; real accounting rules are unknown |
| Q4 | Where does merchant/customer policy come from and who may edit it? | Cascade needs an owner and an audit trail |
| Q5 | Which settlement *phases* exist per region, and what are their individual deadlines? | One `settle` step and one cut-off exist; real settlement is multi-phase |
| Q6 | What is the money type and rounding policy? | The spike uses floats; this must be decided before real money |
| Q7 | What regulatory duties apply (KYC/AML, record retention, audit)? | Shapes the durable store and `needs_review` workflow |
| Q8 | How should provider quote and call costs be accounted (rate limits, fees to us)? | Hedging is not free |
| Q9 | What are the real latency/throughput targets? | Decides the performance work and when Rust might matter |
| Q10 | Who resolves `needs_review`, how fast, with what tools? | UC-4 is unbuilt |

## 9. Risks

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | Floats used for money and rates | certain | high | Integer minor units or Decimal before any real flow |
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
| R18 | CI has never run on GitHub | medium | low | Run and fix; make required |

## 10. Roadmap

Status as of 2026-10-15. See also [ROADMAP.md](ROADMAP.md).

| Phase | Content | Status |
|---|---|---|
| 0 | Harness, gates | ✅ done |
| 1 | Provider model, fake, health, breaker | ✅ done |
| 2.1 | Policy + quote-and-select | ✅ done |
| 2.2 | Lifecycle, undo, reconcile, narrative, dashboard | ✅ done |
| 2.3 | Cascading policy, standing guarantees, deadlines, regional cut-offs, settlement step | ✅ done (multi-phase settlement and timezone data remain) |
| 3 | Sentinel (configurable, persistent), event-sourced durable log, durable store, supervision of providers; performance metrics | 🟡 metrics and charts done; the rest 🔲 |
| 4 | Load / soak / chaos testing, system suite, regression pack, CI | 🟡 system suite, regression pack, mutation testing, CI workflow done; load testing 🔲 |
| 5 | Distribution: single owner per transaction across nodes, partition convergence, provider discovery | 🔲 |
| 6 | Production hardening: auth, TLS, money types, compliance, operations runbook | 🔲 |

Near-term: run CI, Dialyzer, load/soak mode, durable store, UC-4 review tooling, timezone data.

## 11. Reading order for a newcomer

1. This document.
2. [architecture/01-use-cases.md](architecture/01-use-cases.md): what the system is for.
3. [architecture/04-design-sequences-and-activities.md](architecture/04-design-sequences-and-activities.md): how it behaves.
4. `lib/actor_tx_spike/payment.ex`, `selector.ex`, `provider/health.ex`: the three modules that matter.
5. Run `mix demo` and break things.
6. [adr/](adr/) for the reasons; [quality/QUALITY_SNAPSHOT.md](quality/QUALITY_SNAPSHOT.md) for the evidence.

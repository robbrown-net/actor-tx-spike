# Requirements

What the system must do, how well, and where each requirement is verified. Requirements come from
the owner's statements (recorded in [ENGINEERING_STORY](ENGINEERING_STORY.md)) and from the
regulatory notes in [compliance/COMPLIANCE_EVIDENCE.md](compliance/COMPLIANCE_EVIDENCE.md). The
use cases ([architecture/01](architecture/01-use-cases.md)) say how people meet the system; this
page lists the obligations behind them. IDs are stable: cite them in tests and commits.

Status: ✅ met and verified · 🟡 partly · 🔲 not built.

## 1. Functional requirements

| ID | Requirement | Use case | Verified by | Status |
|---|---|---|---|---|
| FR-01 | Convert and pay a customer's amount through third-party providers: quote, accept, authorise, pay, settle | UC-1 | `test/unit/payment_test.exs`, `test/system/live_system_test.exs` (main scenario) | ✅ |
| FR-02 | Ask providers for quotes concurrently within a deadline; rank usable quotes under a policy; never call a provider whose health state the policy excludes; fall back to a baseline when no quote is usable | UC-1 | `selector_test.exs`, `policy_test.exs`, `integration/selection_test.exs` | ✅ |
| FR-03 | A definite failure undoes what was done (real undo operations) and fails over to the next candidate | UC-1 7a | `payment_test.exs`, `integration/payment_invariants_test.exs`, lab scenario *Failover: authorise rejected* | ✅ |
| FR-04 | Never pay twice: a call whose answer is lost is reconciled by asking the provider, never repeated blindly; an unresolvable pay or settle is never failed over and goes to `needs_review` | UC-1 8b, 8c | `payment_test.exs`, invariants fault-storm test, lab scenarios *Pay: the answer is lost*, *never confirmable*, *Settle: refused* | ✅ |
| FR-05 | Every provider step carries a stable idempotency key; submitting the same transaction id twice is one transaction | UC-1 | `payment_test.exs`, `Replay.Rules` `idempotency_keys`, lab scenarios *duplicate submit*, *re-send same key* | ✅ |
| FR-06 | Model providers, do not trust them: circuit breaker, rolling latency and error stats, drift quarantine by a sentinel, gradual recovery | UC-3 | `provider/health_test.exs`, `provider/server_test.exs`, `integration/provider_resilience_test.exs`, system test (drift) | ✅ (sentinel is demo-level, see UC-3) |
| FR-07 | Routing objectives cascade global, merchant (a paid privilege), customer; most specific wins; invalid overrides are refused when built | UC-5 | `policy_set_test.exs`, `policy_test.exs`, system test (cascade) | ✅ |
| FR-08 | Honour standing guarantees: record the margin or exposure of each transaction against a merchant's guaranteed rate, per period | UC-5 | `guarantee_ledger_test.exs`, `Replay.Rules` `margin_arithmetic` | ✅ |
| FR-09 | Honour regional settlement cut-offs: stop and undo when the cut-off passes before settlement | UC-1 | `cutoff_test.exs`, `payment_test.exs` (deadline), system test (deadline) | ✅ (no timezone database) |
| FR-10 | Money is exact: integer minor units, scaled-integer rates, half-even rounding, decimals parsed exactly at the edge | all | `money_test.exs`, regression assertion on fee units | ✅ |
| FR-11 | Keep a tamper-evident audit log: append-only, hash-chained, verifiable, with card numbers scrubbed from free text | UC-7 | `audit_test.exs`, `mix audit.verify`, `integration/evidence_test.exs` | ✅ (anchoring off-host not built) |
| FR-12 | Write-ahead and fail closed: a record of each money-moving call is durable before the call; if it cannot be made durable, no money moves | UC-7 | `audit_failclosed_test.exs`, lab scenarios *Audit log down* | ✅ |
| FR-13 | Resume open transactions from the log after a restart without paying twice; escalate what the log cannot settle | UC-10 | `recovery_test.exs` (cut-point and random-kill), lab restart scenarios | ✅ (started by the host, not automatic) |
| FR-14 | Replay a day from the log: the system at any moment, verification of 11 rules in hindsight, what-if of a different policy, comparison with another system's day | UC-9 | `replay_test.exs`, `integration/replay_test.exs` | ✅ (quote-level; no re-execution engine) |
| FR-15 | Jurisdiction is a hard constraint before scoring: eligible providers, permitted instruments and customer types per corridor; strictest region wins; refused transactions call no one | UC-11 | `eligibility_test.exs`, `integration/jurisdiction_test.exs`, lab scenarios *India*, *China*, *Forward to India is refused* | ✅ (matrix unverified by counsel) |
| FR-16 | Data residency: each transaction is recorded only in the logs its decision names; provable across the logs | UC-11 | `audit_router_test.exs`, `Replay.Residency`, `mix replay residency`, lab *Logs verify* | ✅ (retention recorded, not enforced) |
| FR-17 | Price each transaction: cost of CPU, memory, network and stages, and money saved by price discovery and margin | UC-8 | `costs_test.exs`, `economics_test.exs`, `Replay.Rules` `economics_consistent` | ✅ (placeholder rate card) |
| FR-18 | Show the system live and allow operator control: one narrative stream, dashboard, performance view, per-transaction stories, provider presets | UC-2, UC-6 | `panel_test.exs`, `metrics_test.exs`, `integration/demo_test.exs`, system tests | ✅ |
| FR-19 | Provide a Design for Test lab: provoke restarts, idempotency, the router and audit-log downtime by state, and judge the outcomes | UC-12 | `integration/lab_test.exs` (17 scenarios) | ✅ |
| FR-20 | Spot and forward instruments for business and retail customers in UK, EU, US, India, Mexico, Brazil, Turkey, Saudi Arabia, Japan, South Korea, China | UC-11 | eligibility rules for the demo corridors only | 🟡 (forward deal lifecycle, retail flows, most country rules not built) |
| FR-21 | Resolve a transaction needing review: a queue, check with the provider, retry a safe step under its original key, resolve as paid / not paid / written off with who and why; write-ahead; rebuilt from the log | UC-4 | `unit/review_test.exs`, `integration/review_test.exs`, system test (UC-4), replay rule `review_recorded` | ✅ (no four-eyes, assignment or deadlines) |
| FR-22 | Sanctions and KYC gates before accept; trade reporting for forwards | | none | 🔲 |
| FR-23 | Send many transactions: a settable number (1 to 10,000), a minimum and maximum amount with a random amount between them, and a settable pause in milliseconds between submissions; one batch at a time; stop; progress counted from how each transaction ends | UC-13 | `unit/demo/bulk_plan_test.exs`, `integration/bulk_test.exs`, system test (bulk send) | ✅ |
| FR-24 | Randomise every setting per transaction (merchant, customer, origin and destination region, instrument, customer type, source and destination currency), with a seed that repeats a batch exactly, and a control that shuffles the header settings once | UC-13 | same | ✅ |
| FR-25 | Carry the source and destination currency on a request and show them in the story and the audit log | UC-13 | `payment_test.exs`, `integration/bulk_test.exs` | ✅ |
| FR-26 | Admission control: at most a configured number of transactions run at once; a submission beyond it is refused at once with *overloaded* and a retry hint, before anything starts or is recorded; slots are freed when a payment ends or dies; the limit can be changed at run time | UC-14 | `unit/admission_test.exs`, `unit/application_test.exs`, `integration/bulk_test.exs`, system test (back-pressure) | ✅ |
| FR-27 | Provider bulkheads protect money in motion: quotes may use 60% of the slots, steps 100%, undo, status and probe 150% | UC-14 | `unit/provider/server_test.exs` | ✅ |
| FR-28 | Wait instead of failing: a busy provider is asked again for a quote until the deadline, and on a step without using an attempt | UC-14 | `unit/selector_test.exs`, `unit/payment_test.exs` | ✅ |
| FR-29 | The sender backs off when refused as overloaded, can cap its own in-flight count, and reports why transactions did not complete | UC-13, UC-14 | `integration/bulk_test.exs` | ✅ |
| FR-30 | Price a payment from the market rate of its currency pair through each provider's own spread | UC-15 | `unit/provider/fake_test.exs`, `integration/currency_test.exs` | ✅ |
| FR-31 | Ask only providers that carry both currencies, and say which were not asked | UC-15 | `unit/selector_test.exs`, `integration/currency_test.exs` | ✅ |
| FR-32 | A region may restrict the currencies a corridor can use (strictest region wins) | UC-15 | `unit/eligibility_test.exs`, `integration/currency_test.exs` | ✅ (no minor-unit exponents per currency) |
| FR-33 | Record each bulk batch's plan and result in the audit log and read them back | UC-13 | `integration/bulk_test.exs`, `unit/replay_test.exs` | ✅ |

## 2. Non-functional requirements

| ID | Requirement | Measure | Verified by | Status |
|---|---|---|---|---|
| NFR-01 | Soft real-time: responses in milliseconds (the reason for Elixir/BEAM; escape route to Rust if tails or CPU demand it) | per-step timeouts, deadline-bound selection | Performance view, unit timing tests; no load test | 🟡 |
| NFR-02 | Availability first: always start with a valid result and only improve it; route around unavailable providers | best-so-far, baseline fallback, breaker | `selector_test.exs`, `provider_resilience_test.exs` | ✅ |
| NFR-03 | Safety over availability for money: when in doubt or when evidence cannot be kept, stop for a person | fail closed, `needs_review` | FR-04, FR-12 tests | ✅ |
| NFR-04 | Zero runtime dependencies | `mix.exs` | review, `mix deps.audit` | ✅ |
| NFR-05 | Audit evidence for PCI DSS and Dodd-Frank | evidence map and gaps | [COMPLIANCE_EVIDENCE](compliance/COMPLIANCE_EVIDENCE.md) | 🟡 (gaps listed there) |
| NFR-06 | Security of the control surface: loopback only, JSON-only POST, 64 KiB body | | `panel_test.exs`; manual review in the quality snapshot | 🟡 (no authentication or TLS: do not expose) |
| NFR-07 | Test gates: unit coverage 100%, integration coverage above 85% | `mix test.unit`, `mix test.integration` | CI | ✅ (unit 100%, integration 86.7%) |
| NFR-08 | Maintainability: cyclomatic complexity at most 4 per function, nesting at most 2, ABC size at most 40, no duplicated block of mass 16 in `lib/` | `mix credo --strict` | CI, `mix quality` ([ADR 0012](adr/0012-code-shape-limits.md)) | ✅ |
| NFR-09 | No compiler warnings; formatted code | `--warnings-as-errors`, `mix format --check-formatted` | CI | ✅ |
| NFR-10 | Everything observable: every component announces what it is doing | `Events` narrative | system tests, dashboard | ✅ |
| NFR-11 | Single node first, without designing distribution out | architecture/05 | review | ✅ (multi-node not built) |
| NFR-12 | Replayability: every decision re-derivable from the journal | `Selector.evaluate/3` shared with replay; replay tests break a day one way at a time | `replay_test.exs` | ✅ |
| NFR-16 | A Java 21 / Spring Boot implementation, converted in end-to-end chunks, held to the same quality bars (complexity 4, nesting 2, duplication, 100% unit and at least 85% integration coverage) with equal or more comprehensive tests | `./jmvn verify`, CI (`java.yml`) | `java/` suites; [java/README.md](../java/README.md), [architecture/13](architecture/13-java-architecture.md) | 🟡 (quote, pay, replay, review, bulk, metrics and the dashboard are converted: 673 unit and 68 integration tests; recovery after restart, CLI tools and four dashboard controls remain) |
| NFR-17 | Extensible domain: a new payment step, a per-corridor or per-provider workflow, an asynchronously confirmed step, a provider capability or a routing criterion is added without editing the engine; the safety rules follow from what a step declares | `Step`/`Workflow`/`Criterion` as values | `WorkflowTest`, `SelectorCriteriaTest`, [ADR 0016](adr/0016-extensible-domain-model.md) | ✅ (Java build; closed enums to be reviewed, see ROADMAP) |
| NFR-13 | Documentation current: diagrams, developer, testing, requirements and user guides | [docs/README](README.md) | review per change | ✅ (as of 2026-10-23, including the Java build) |
| NFR-14 | Handle volume: a batch of up to 10,000 transactions can be sent without the sender or the system crashing, and every transaction is accounted for | `Demo.Bulk` counts each to completed, failed or needs review | `integration/bulk_test.exs`, `mix load` | ✅ |
| NFR-15 | Under a burst with the production admission limit, nearly everything admitted completes and nothing is lost or half-done | with the limit at 100 against the demo providers: 99.8% of 2,000 complete, 0 lost ([LOAD_RESULTS](quality/LOAD_RESULTS.md)) | `mix load` (measurement, not a gate) | 🟡 (fake providers, no audit log in the measurement; re-measure with real ones) |

## 3. Traceability rules

* A new behaviour gets a requirement ID here, a test that names it, and a use-case line if people see it.
* A fixed defect gets a `:regression` test (see [TESTING](TESTING.md)).
* A requirement marked 🔲 or 🟡 has its gap in the [ROADMAP](ROADMAP.md) known gaps.

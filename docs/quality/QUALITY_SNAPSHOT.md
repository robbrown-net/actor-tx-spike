# Quality snapshot

Measured **2026-10-21** (Bulk send added on top of the 2026-10-20 code-shape refactor) ([ADR 0012](../adr/0012-code-shape-limits.md)): stricter complexity, nesting, ABC and duplication limits, on top of exact money, the audit chain, economics, replay, recovery, jurisdiction and the Test lab.
Everything here was produced by tools run against the repository; nothing is estimated
unless marked.

## 1. Headline

| Area | Result | Target | Verdict |
|---|---|---|---|
| Unit coverage (line) | **100.0%** (all measured modules) | 100% | ✅ |
| Integration coverage (line) | **86.7%** | > 85% | ✅ thin headroom (~1.7 pt) |
| System tests | **12 scenarios**, real process over HTTP/SSE, all pass | n/a | ✅ |
| Regression pack | **23 tests** tagged to defects found | n/a | ✅ |
| Mutation score (sampled) | **88 of 90** valid mutants killed over the pure core and the new modules; the 2 survivors are equivalent mutants (section 4) | n/a | ✅ with caveat |
| Tests | 380 non-system (346 unit, 34 integration) plus 12 system, 0 failures | n/a | ✅ |
| Static analysis (Credo strict) | **0 issues**, 1,364 modules and functions, **at the new limits below** | 0 | ✅ |
| Cyclomatic complexity per function | **max 4** (limit 4; was 9) | ≤ 4 | ✅ |
| Nesting depth | **max 2** | ≤ 2 | ✅ |
| ABC size per function (`lib/`) | **max 40** (limit 40; was unlimited) | ≤ 40 | ✅ |
| Duplicated code (`lib/`) | **none at mass 16** (was none at mass 40) | none | ✅ |
| Compilation | `--warnings-as-errors` clean | clean | ✅ |
| Dependency vulnerabilities | **none** (mix_audit, hex.audit) | none | ✅ |
| Security scan (Sobelow) | 2 findings, both non-applicable / false positive | n/a | ✅ with notes |
| Runtime dependencies | **0** | minimise | ✅ |
| CI | runs every gate above; latest run on the code-shape commit: see the repository | green | check |

### The limits, in one place

| Limit | Value | Applies to | Before | Enforced by |
|---|---|---|---|---|
| Cyclomatic complexity | 4 | `lib/`, `test/` | 9 (Credo default) | `.credo.exs`, CI, `mix quality` |
| Nesting | 2 | `lib/`, `test/` | 2 (default) | same |
| ABC size | 40 | `lib/` | not enabled | same |
| Duplicated code mass | 16 | `lib/` | not enabled (40 default if on) | same |
| Unit coverage | 100% | all but listed tooling | 100% | `mix test.unit` |
| Integration coverage | > 85% | all but `Mix.Tasks.*` | > 85% | `mix test.integration` |

See [DEVELOPER_GUIDE](../DEVELOPER_GUIDE.md#3-quality-limits-and-how-they-are-enforced) and [ADR 0012](../adr/0012-code-shape-limits.md).

## 2. Size (SLOC)

Non-blank, non-`#`-comment lines (docstrings included), same counter as the 2026-10-17 snapshot.

| Area | Files | Code |
|---|---|---|
| `lib/` (Elixir) | 52 | **6,806** |
| `test/` (Elixir, incl. `test/support`) | 42 | **5,766** |
| `priv/panel.html` | 1 | 767 (HTML/CSS/JS) |
| `scripts/mutate.py` | 1 | ~120 (Python) |
| Documentation (Markdown) | 31 | |

Test-to-code ratio **0.85**. Largest modules: `Payment` 803, `Demo.Lab.Scenarios` 466, `Demo.Lab` 416, `Demo` 410, `Replay.Rules` 280.
The refactor to the limits added 6 small modules (`Selector.Narrator`, `Eligibility.Parser`, `Validation`, `Poll`, test helper `Wait`, and the unit tests for them) and removed duplicated parsing, polling and quote-rebuilding code.

## 3. Coverage detail

Line coverage via Erlang `cover` (the Mix built-in). **Line coverage cannot see untaken
branches inside a covered line**; that gap is why mutation testing was added (section 4).

| Module | Unit | Integration |
|---|---|---|
| ActorTxSpike (API) | 100% | 73% |
| Application, Provider (behaviour), Quote, Selection, Candidate, TransactionSupervisor | 100% | 100% |
| Payment | 100% | 91% |
| Selector | 100% | 88% |
| Policy | 100% | 79% |
| PolicySet | 100% | 88% |
| Cutoff | 100% | 88% |
| Guarantee | 100% | 82% |
| Ledger | 100% | 95% |
| Events | 100% | 83% |
| Narrative | 100% | 13% |
| Money | 100% | 95% |
| Audit | 100% | 73% |
| Costs | 100% | 100% |
| Economics | 100% | 94% |
| Journal / Replay (+ Tx, Rules, WhatIf, Compare, Report) | 100% | each exercised by one end-to-end replay test; see `mix test.integration` |
| Panel | 100% | 73% |
| Provider.Health | 100% | 86% |
| Provider.Server | 100% | 94% |
| Provider.Fake | 100% | 95% |
| Metrics / Stats / Rollup / VM | 100% | 91% / 63% / 94% / 96% |
| Demo / Demo.Handler | excluded (tooling) | 90% / 100% |
| Mix.Tasks.Demo | excluded everywhere (thin entry point) | n/a |
| **Total** | **100%** | **86.7%** |

Exclusions are explicit in `mix.exs`. `Narrative` is covered by unit tests only, which is
why its integration figure is low.

### Suites

| Suite | Command | Count | Measures |
|---|---|---|---|
| Unit | `mix test.unit` | 346 | line coverage gate 100% |
| Integration | `mix test.integration` | 34 | line coverage gate > 85% |
| System | `mix test.system` | 12 | scenarios (below), no line coverage |
| Regression pack | `mix test.regression` | 23 | pass/fail |
| Mutation (on demand) | `python3 scripts/mutate.py` | sampled | kill rate |
| Test lab | `mix demo --lab` | 17 scenarios | also run as an integration test |

### System scenario coverage (use case → test)

| Use case / extension | System test |
|---|---|
| Boot, dashboard, five-provider snapshot | boots and serves… |
| UC-1 main scenario incl. settlement, coherent narrative | main scenario |
| UC-1 ext 7a authorise rejected → undo → failover | authorise rejected… |
| UC-1 ext 8b lost payment answer reconciled | lost payment answer… |
| UC-1 ext 8c unresolvable payment → needs_review, no failover | unresolvable payment… |
| UC-3 drift quarantine by the sentinel | version drift… |
| UC-5 policy validation; cascade, entitlement, guarantee ledger | invalid policy…; cascade… |
| UC-2 auto traffic | automatic traffic… |
| UC-13 bulk send: seeded random batch over HTTP, fully accounted | bulk send… |
| Deadline: cut-off passes mid-transaction | deadline… |
| **Not covered by a system test:** UC-4 review workflow (unbuilt), settlement refusal, performance view | |

### Defects found, and the test that guards each

| # | Defect | Guard |
|---|---|---|
| 1 | Recovered provider re-admitted too eagerly / quiet provider never leaves degraded | `Health` gradual-recovery tests |
| 2 | Malformed persisted snapshot crashed boot (legacy store, removed) | none: code removed; re-test with the durable store |
| 3 | Tests left partial snapshots in the persisted store file | none: store removed |
| 4 | Caller dying mid-call leaked a concurrency / probe slot | `Server` caller-dies test |
| 5 | Transaction ids collided across demo restarts, returning a stale result | idempotent-ingress test; demo uses unique prefixes |
| 6 | Late status confirmation of a payment ignored | `Payment` stale-status test |
| 7 | **A provider server dying mid-transaction crashed the Payment process** (found while writing the test suite) | `Payment` dead-provider regression test |
| 8 | Boundary and message conditions untested despite 100% line coverage | mutation-driven tests in Policy, Health, Selector, Ledger, Cutoff |
| 9 | Ledger totals returned integer 0 vs float | float-typed totals tests |
| 10 | Demo "heal" re-normalised already-converted fees (a 100x unit slip) | regression assertion in the demo tests |
| 11 | The audit scrubber redacted the hash chain's own digit strings | audit test: only message and data are scrubbed |
| 12 | Deadlock between the audit writer stopping and the event server flushing | `audit_failclosed` and `events` tests (unregister is a cast; the barrier flushes from the caller) |
| 13 | Per-record fsync starved the barrier under load (cascading failures) | group-commit tests and the lab's downtime scenarios |
| 14 | Two processes appended to one log and broke the chain | `Audit.Lock` tests |
| 15 | A restarted router lost its routing table, so a local-only transaction could leak to GLOBAL | `audit_supervised` restart test |
| 16 | `Replay.load(verify: false)` crashed on a log that is not JSON | replay test (`{:error, :malformed_log}`) |
| 17 | `Payment.snapshot/1` raised when its process was killed mid-call | Test lab crash scenarios (integration) |
| 18 | System test UC-3 asserted drift before a baseline version was recorded | the test waits for the baseline |

## 4. Mutation testing

`scripts/mutate.py` applies one small change at a time (flip a comparison, swap `and`/`or`,
drop a negation, off-by-one, change an error message) to the pure core and runs the relevant unit
tests. A mutant that makes the code hang counts as killed (the run has a timeout).

Re-run 2026-10-20/21 after the refactor and the Bulk send plan, with the new modules added as targets:

| File | Valid mutants | Killed | Notes |
|---|---|---|---|
| `policy.ex` | 24 | 24 | |
| `provider/health.ex` | 11 | 11 | |
| `selector.ex` | 1 | 1 | the logic that carried operators moved to `Policy`, `Narrator` and clauses; fewer mutable operators remain |
| `cutoff.ex`, `policy_set.ex`, `guarantee.ex`, `metrics/stats.ex` | 2, 3, 3, 2 | all | |
| `ledger.ex` | 5 | 4 | 1 equivalent mutant (`< 0` vs `<= 0` adds 0 to a sum), as before |
| `validation.ex` (new) | 3 | 3 | 1 invalid (does not compile) |
| `eligibility/parser.ex` (new) | 11 | 11 | first run left 3 survivors (error wording not asserted); the tests now assert the exact messages |
| `money.ex` | 9 | 8 | first run left 2: a zero divisor is now pinned by a test; the other (`shift >= 0` vs `> 0`) is equivalent, both branches give the same result when the shift is 0 |
| `poll.ex` (new) | 4 | 4 | |
| `demo/bulk/plan.ex` (new) | 12 | 12 | first run left 3 survivors (a zero amount accepted; two error messages unasserted); pinned by tests |

Caveats: sampled (up to 30 mutants per file), regex-based, run on demand. It covers the pure
core, not `Payment`, `Panel`, `Demo` or `Recovery`. A mature tool is not usable here (`muzak` is
incompatible with Elixir 1.18).

## 5. Static analysis

### Credo (`mix credo --strict`, 1,273 modules and functions): **no issues**, at the limits in section 1.

### Complexity (Credo's cyclomatic complexity, `lib/`)
Cyclomatic complexity is counted per function clause: 1 plus each `if`/`unless`, each `case`/`cond`/`with` clause beyond the first, and each `&&`, `||`, `and`, `or`.

| Threshold | Functions above it in `lib/` |
|---|---|
| > 4 | **0** (the gate) |
| > 3 | 34 |
| > 2 | 130 |
| > 1 | 261 |

Before the refactor (limit then 9, measured with the limit set to 5): 18 functions were above 5; the worst
were `Replay.Tx.summarize/1` (9), `PolicySet.resolve/2` (8), `Replay.Rules.margin_arithmetic/3` (8),
`Provider.Fake.effect/4` (8), `Mix.Tasks.Replay.run/1` (8). Fifteen more functions were above ABC size 40, the largest
`Selector.select/4` at 82, `Demo.Lab.Scenarios.all/0` at 68, `Metrics.take_sample/1` at 63.
All were reshaped; see [ADR 0012](../adr/0012-code-shape-limits.md) for the patterns.

### Duplication (Credo `DuplicatedCode`, `lib/`)
| Mass threshold | Duplicates in `lib/` |
|---|---|
| 40 (Credo default) | 0 |
| 16 (the gate) | **0** (before: 4 clusters: `recovery.ex`/`replay/tx.ex` quote rebuilding, `demo.ex` provider registry, `replay/residency.ex`/`scenarios.ex` log lookup, `replay.ex` grouping) |

`test/` is not subject to the duplication or ABC limits by design (ADR 0012); its remaining repetition is arrange steps.

## 6. Security

| Check | Result |
|---|---|
| `mix deps.audit` / `mix hex.audit` | No vulnerabilities; no retired packages |
| Sobelow | 2 findings (below) |
| Dangerous calls in `lib/` | no `String.to_atom`, `binary_to_term`, `Code.eval*`, `System.cmd`, `:os.cmd`; one `apply/3` on a fixed adapter module with an operation allow-list |

Sobelow findings:

1. *HTTPS not enabled* (`config/prod.exs`): a Phoenix check; no Phoenix endpoint here. **Not applicable**, but see the dashboard notes.
2. *Directory traversal in `File.read!`* (`Panel.page/0`, low confidence): the path is a constant under `priv/`; no user input reaches it. **False positive.**

Manual review of the dashboard server (the only network-facing code):

| Aspect | State |
|---|---|
| Binding | Loopback only by default |
| Authentication / authorisation | **None.** Anyone on the machine can use the control API |
| CSRF | POSTs require `application/json` (forces a CORS preflight a foreign page cannot pass) |
| Body size | Limited to 64 KiB |
| Connection handling | One process per connection; no connection limit |
| TLS | None |
| Information exposure | Narrative contains amounts and provider details; loopback only |

**Do not bind the dashboard to a non-loopback address without adding authentication and TLS.**

## 7. Process quality

| Aspect | State |
|---|---|
| CI | GitHub Actions: compile with warnings as errors, format, Credo strict (which carries the code-shape limits), audits, unit and integration gates, regression, system |
| Format | `mix format --check-formatted` clean |
| Type checking | none (no Dialyzer yet); typespecs partial |
| Branch coverage | not available in the toolchain; mutation testing used instead |
| Load / soak testing | none; the metrics layer is ready to observe one |

## 8. Recommended next quality actions

1. Make the CI workflow a required check (branch protection).
2. Add Dialyzer (dialyxir) and complete typespecs.
3. Build a load / soak mode and use the Performance view to set SLOs.
4. Widen mutation testing to `Payment` (needs an equivalent-mutant review).
5. Authentication and connection limits before any non-loopback exposure.
6. Record-and-replay contract tests once a real provider adapter exists.

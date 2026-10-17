# Quality snapshot

Measured **2026-10-17** after exact money, the audit chain, the economics ledger and replay.
Everything here was produced by tools run against the repository; nothing is estimated
unless marked.

## 1. Headline

| Area | Result | Target | Verdict |
|---|---|---|---|
| Unit coverage (line) | **100.0%** (all measured modules) | 100% | ✅ |
| Integration coverage (line) | **87.5%** | > 85% | ✅ (headroom ~2 pts) |
| System tests | **10 scenarios**, real process over HTTP/SSE, all pass | n/a | ✅ (no line coverage by nature) |
| Regression pack | **23 tests** tagged to defects found | n/a | ✅ |
| Mutation score (sampled) | **100%** of 93 valid mutants killed over the pure core, after strengthening | n/a | ✅ with caveat |
| Tests | 267 (235 unit, 21 integration, 11 system), 0 failures | n/a | ✅ |
| Static analysis (Credo strict) | **0 issues**, 729 functions | 0 | ✅ |
| Compilation | `--warnings-as-errors` clean | clean | ✅ |
| Dependency vulnerabilities | **none** (mix_audit, hex.audit) | none | ✅ |
| Security scan (Sobelow) | 2 findings, both non-applicable / false positive | n/a | ✅ with notes |
| Runtime dependencies | **0** | minimise | ✅ |
| CI | first run on GitHub Actions (2026-10-15, commit `734727d`): **success** | green | ✅ |

## 2. Size (SLOC)

Non-blank, non-`#`-comment lines (docstrings included). Re-measured 2026-10-17 with this simpler counter, so figures are not comparable with the 2026-10-15 snapshot, which excluded docstrings.

| Area | Files | Code | Docs/comments | Blank |
|---|---|---|---|---|
| `lib/` (Elixir) | 38 | **4,276** | | |
| `test/` (Elixir) | 30 | **3,839** | | |
| `priv/panel.html` | 1 | 514 (HTML/CSS/JS) | | |
| `scripts/mutate.py` | 1 | ~110 (Python) | | |
| Documentation (Markdown) | 30+ | | | |

Test-to-code ratio **0.90**. Largest modules: `Payment` 603, `Demo` 307, `Economics` 155, `Audit` 133.

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
| **Total** | **100%** | **87.5%** |

Exclusions are explicit in `mix.exs`. `Narrative` is covered by unit tests only, which is
why its integration figure is low.

### Suites

| Suite | Command | Count | Measures |
|---|---|---|---|
| Unit | `mix test.unit` | 173 | line coverage gate 100% |
| Integration | `mix test.integration` | 18 | line coverage gate > 85% |
| System | `mix test.system` | 10 | scenarios (below), no line coverage |
| Regression pack | `mix test.regression` | 23 | pass/fail |
| Mutation (on demand) | `python3 scripts/mutate.py` | sampled | kill rate |

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

## 4. Mutation testing

`scripts/mutate.py` applies one small change at a time (flip a comparison, swap `and`/`or`,
drop a negation, off-by-one) to the pure core and runs the relevant unit tests.

| File | First run | After new tests |
|---|---|---|
| `policy.ex` | 80% (5 survivors) | 100% (26/26) |
| `provider/health.ex` | 73% (3 survivors) | 100% (11/11) |
| `selector.ex` | 50% (2 survivors, 3 invalid) | 100% (4/4 valid) |
| `cutoff.ex` | 86% (1 survivor) | 100% after assertion on exact message |
| `ledger.ex` | 60% (2 survivors) | 100% of non-equivalent; 1 equivalent mutant (`< 0` vs `<= 0` adds 0 to a sum) |
| `policy_set.ex`, `guarantee.ex`, `metrics/stats.ex` | 100% | n/a |

Caveats: sampled (up to 25–30 mutants per file), regex-based, run on demand. It covers the
pure core, not `Payment`, `Panel` or `Demo`. A mature tool is not usable here (`muzak` is
incompatible with Elixir 1.18).

## 5. Static analysis

### Credo (`mix credo --strict`, 508 functions): **no issues**.

### Complexity (own AST analysis; cyclomatic = 1 + decision points)
| Measure | Value |
|---|---|
| Functions / clauses | 228 / 365 |
| Max per clause | **7** (`Provider.Fake.effect/4`) |
| Mean per clause | 1.40 |
| Clauses over 10 | 0 |
| Max per function, all clauses summed | 16 (`Payment.handle_result/3`, 13 clauses; `Demo.do_control/2`, 7 clauses) |
| Functions over 10 (summed) | 4 (`do_control/2`, `handle_result/3`, `Demo.preset/4`, `Panel.route/6`) |

The hot spots are tables of small pattern-matching clauses, not deeply nested logic.
`Payment.handle_result/3` should move to a step table before the next lifecycle change.

### Duplication (6-line normalised windows)
| Area | Repeated windows | Lines involved |
|---|---|---|
| `lib/` | 0 | **0%** |
| `test/` | 9 | ~48 lines (**1.8%**) |

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
| CI | GitHub Actions workflow ran green on `main` (format, Credo, audits, unit and integration gates, regression, system) |
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

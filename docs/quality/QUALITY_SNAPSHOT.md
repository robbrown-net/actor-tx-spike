# Quality snapshot

Measured **2026-10-15** on commit `b1286f2` plus the documentation pass (no production code changed
except three trivial lint fixes). Everything here was produced by tools run against the repo;
nothing is estimated unless marked.

## 1. Headline

| Area | Result | Target | Verdict |
|---|---|---|---|
| Unit coverage (line) | **100.0%** (17 modules) | 100% | ✅ |
| Integration coverage (line) | **86.0%** | > 85% | ✅ (thin headroom) |
| System-test coverage | **none: suite does not exist** | n/a | ⚠️ gap |
| Regression-pack coverage | **none: pack does not exist** | n/a | ⚠️ gap |
| Tests | 126 (111 unit, 15 integration), 0 failures | n/a | ✅ |
| Static analysis (Credo strict, 69 checks) | **0 issues** | 0 | ✅ |
| Dependency vulnerabilities (mix_audit, hex.audit) | **none** | none | ✅ |
| Security scan (Sobelow) | 2 findings, both non-applicable / false positive | n/a | ✅ with notes |
| Runtime dependencies | **0** | minimise | ✅ |

## 2. Size (SLOC)

Non-blank, non-comment, non-docstring lines.

| Area | Files | Code | Docs/comments | Blank |
|---|---|---|---|---|
| `lib/` (Elixir) | 17 | **1,724** | 278 | 413 |
| `test/` (Elixir) | 14 | **1,479** | 37 | 362 |
| `priv/panel.html` (HTML/CSS/JS) | 1 | 243 | n/a | n/a |
| Documentation (Markdown) | 20+ | see docs | | |

Test-to-code ratio: **0.86**. Largest modules: `Payment` 401, `Demo` 217, `Provider.Fake` 187,
`Panel` 169, `Selector` 167. Average module ≈ 100 lines.

## 3. Coverage detail

Line coverage via Erlang `cover` (the Mix built-in). **Line coverage only: it cannot see untaken
branches inside a covered line**, so 100% is necessary, not sufficient.

| Module | Unit | Integration |
|---|---|---|
| ActorTxSpike | 100% | 60% |
| ActorTxSpike.Application | 100% | 100% |
| ActorTxSpike.Events | 100% | 83% |
| ActorTxSpike.Narrative | 100% | 13% |
| ActorTxSpike.Panel | 100% | 73% |
| ActorTxSpike.Payment | 100% | 88% |
| ActorTxSpike.Policy | 100% | 79% |
| ActorTxSpike.Provider (behaviour) | 100% | 100% |
| ActorTxSpike.Provider.Fake | 100% | 96% |
| ActorTxSpike.Provider.Health | 100% | 86% |
| ActorTxSpike.Provider.Quote | 100% | 100% |
| ActorTxSpike.Provider.Server | 100% | 94% |
| ActorTxSpike.Selector | 100% | 86% |
| ActorTxSpike.Selection (+Candidate) | 100% | 100% |
| ActorTxSpike.TransactionSupervisor | 100% | 100% |
| ActorTxSpike.Demo / Demo.Handler | excluded (tooling) | 98% / 100% |
| Mix.Tasks.Demo | excluded everywhere | n/a |
| **Total** | **100%** | **86.0%** |

**How honest is the 100%?** `Demo` and `Demo.Handler` are excluded from the unit gate by design
(they are demo tooling, exercised by integration tests). The exclusion list is in `mix.exs`.

### Suites that do not exist

| Suite | Status | What it would contain |
|---|---|---|
| **System tests** | missing | Boot the real demo; drive it purely over HTTP/SSE (submit, auto traffic, fault injection, policy change); assert the narrative and outcomes; run for a soak period. |
| **Regression pack** | missing | A tagged set pinning every defect found so far (below) so it can run on its own as a fast pre-merge check. |

Defects found so far, and the test that currently guards each (candidates for the tagged pack):

| # | Defect | Guarding test today |
|---|---|---|
| 1 | Recovered provider re-admitted too eagerly / quiet provider never leaves degraded | `Health` "recovery is gradual…" |
| 2 | Malformed persisted snapshot crashed application boot (legacy store, since removed) | none: code removed; re-test when the durable store is built |
| 3 | Tests left partial snapshots in the persisted store file, breaking the next boot | none: store removed |
| 4 | Caller dying mid-call leaked a concurrency slot / probe slot | `Server` "a caller that dies mid-call releases its slot…" |
| 5 | Transaction ids collided across demo restarts, returning a stale result | `Demo` tests use a unique prefix; real fix (globally unique ids) is open |
| 6 | Late status confirmation of a payment was ignored | `Payment` "…stale status is still honoured" |
| 7 | Default-argument functions hid unreachable lines from coverage | covered by the 100% gate |

## 4. Static analysis

### Credo (`mix credo --strict`, 69 checks, 31 files, 370 functions)
**No issues.** (Three trivial findings from the first run were fixed: `Enum.map_join`, alias ordering, nesting depth.)

### Complexity (own AST analysis; cyclomatic = 1 + decision points)
| Measure | Value |
|---|---|
| Functions / clauses | 159 / 270 |
| Max per clause | **7** (`Provider.Fake.effect/4`) |
| Mean per clause | 1.35 |
| Clauses over 10 | 0 |
| Max per function, all clauses summed | 16 (`Payment.handle_result/3`, 13 clauses) |
| Functions over 10 (summed) | 3 (`handle_result/3`, `Demo.preset/4`, `Demo.do_control/2`) |

The summed hot spots are tables of small clauses (pattern-matching dispatch), not deeply nested
logic. `Payment.handle_result/3` is the one to watch as slice 3 adds more paths.

### Duplication (6-line normalised windows)
| Area | Repeated windows | Lines involved |
|---|---|---|
| `lib/` | 0 | **0%** |
| `test/` | 8 | ~46 lines (**3.0%**), mostly repeated provider/Server setup helpers |

Credo's duplicated-code check also reports nothing.

## 5. Security

| Check | Result |
|---|---|
| `mix deps.audit` (mix_audit) | No vulnerabilities |
| `mix hex.audit` | No retired or advisory packages |
| Sobelow | 2 findings (see below) |
| Dangerous calls in `lib/` | no `String.to_atom`, `binary_to_term`, `Code.eval*`, `System.cmd`, `:os.cmd`; one `apply/3` on a fixed adapter module and operation allow-list |

Sobelow findings:

1. *HTTPS not enabled* (`config/prod.exs`): Phoenix check; this project has no Phoenix endpoint. **Not applicable**, but see risk R-4.
2. *Directory traversal in `File.read!`* (`Panel.page/0`, low confidence): the path is a compile-time constant under `priv/`; no user input reaches it. **False positive.**

Manual review of the dashboard server (the only network-facing code):

| Aspect | State |
|---|---|
| Binding | Loopback only by default (`127.0.0.1`) |
| Authentication / authorisation | **None.** Anyone on the machine can use the control API |
| CSRF | POSTs require `Content-Type: application/json` (forces a CORS preflight a foreign page cannot pass) |
| Body size | Limited to 64 KiB; invalid length closes the connection |
| Connection handling | One process per connection; no connection limit, no read-timeout beyond 5 s on request parsing |
| TLS | None |
| Information exposure | Narrative may contain amounts and provider details; not for exposure beyond loopback |

**Do not bind the dashboard to a non-loopback address without adding authentication and TLS.**

## 6. Process quality

| Aspect | State |
|---|---|
| CI | none: gates run locally only |
| Pre-commit gate | manual (`mix test.unit && mix test.integration`) |
| Format | `mix format --check-formatted` clean |
| Type checking | none (no Dialyzer yet); typespecs partial |
| Branch / mutation coverage | not measured |
| Performance / load tests | none |

## 7. Recommended next quality actions

1. Add CI running format, Credo, both gates, mix_audit.
2. Create the system suite and tagged regression pack.
3. Add Dialyzer (dialyxir) and complete typespecs.
4. Add mutation testing to test whether the 100% really bites.
5. Add a load/soak test feeding the metrics design in [07](../architecture/07-observability-and-performance.md).
6. Authentication and connection limits before any non-loopback exposure.

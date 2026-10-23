# Testing guide

For testers and for anyone writing a test. What is tested, with what, how to run it, how to
provoke the hard cases by hand, and what is not covered.

## 1. The layers

| Layer | Where | Run with | Count (2026-10-23) | Gate | What it proves |
|---|---|---|---|---|---|
| Unit | `test/unit` | `mix test.unit` | 394 | 100% line coverage | each module in isolation, incl. every error clause |
| Integration | `test/integration` | `mix test.integration` | 47 | above 85% line coverage | modules together: a real day recorded, replayed, recovered; jurisdiction; the lab's 17 scenarios |
| System | `test/system` | `mix test.system` | 14 | scenarios pass | the real system as an OS process, driven only over HTTP/SSE |
| Regression | tagged `:regression` | `mix test.regression` | 23 | pass | one test per defect found so far |
| Mutation | `scripts/mutate.py` | on demand | sampled | all valid mutants killed | the tests would notice if the code were wrong (line coverage cannot say this) |
| Static | `.credo.exs` | `mix credo --strict` | n/a | no issues | style, design and the [code-shape limits](DEVELOPER_GUIDE.md#3-quality-limits-and-how-they-are-enforced) |
| Load | `mix load` | `mix load --admission none,200,100` | n/a | measurement, no gate | how many of a burst complete at each admission limit; see [LOAD_RESULTS](quality/LOAD_RESULTS.md) |
| Lab | the Test lab tab | `mix demo --lab` | 17 scenarios | all pass | restarts, idempotency, router, audit-log downtime, by hand or by scenario |

`mix quality` is the quick pre-commit check (format, Credo strict, unit gate). CI runs everything above
except mutation and the lab (the lab's scenarios run inside the integration suite).

## 2. Thresholds the tests are held to

| Threshold | Value | Where it is enforced |
|---|---|---|
| Unit line coverage | 100% of measured modules | `mix test.unit` (`mix.exs`) |
| Integration line coverage | above 85% (last measured 86.7%) | `mix test.integration` |
| Cyclomatic complexity per function, tests included | 4 or less | `.credo.exs` |
| Nesting depth, tests included | 2 or less | `.credo.exs` |
| Mutation kill rate, sampled pure core | every valid mutant | `scripts/mutate.py` |
| Warnings | none | `--warnings-as-errors` |

Not applied to tests: ABC size and duplicate-code limits (a test reads top to bottom with its
set-up visible, on purpose). Coverage exclusions are listed in `mix.exs`: the demo world and lab
(`Demo`, `Demo.Handler`, `Demo.Lab`, `Demo.Lab.Scenarios`) are out of the *unit* gate only and are
exercised by integration; `Mix.Tasks.*` entry points and `ActorTxSpike.Wait` are never measured.

Line coverage cannot see an untaken branch inside a covered line. That is the reason for mutation
testing and for the invariant tests below.

## 3. What the tests rely on

* **Pure decision code** (`Policy`, `Selector.evaluate/3`, `Eligibility`, `Recovery.plan/1`,
  `Provider.Health`) is tested exhaustively and cheaply.
* **A scriptable fake provider** (`Provider.Fake`): modes per operation (ok, timeout, reject,
  error, in-doubt, unknown), mode lists consumed call by call, latency, version drift. It records
  every effect so a test can ask what the provider *actually did*, not what the system believed.
* **Invariant tests** carry the weight: a randomised fault storm asserts "never paid twice, a
  failed transaction was never paid, every failover was fully undone"; the recovery tests cut a
  recorded transaction at every point and at random kill points, recover, and assert the same.
* **Replay over real days**: transactions are run through the real system with the audit log on,
  the log is loaded back, and each rule is proven by breaking a good day in exactly one way and
  expecting exactly that violation.
* **Waiting**: `ActorTxSpike.Wait.until!/2` (tests, in `test/support`) over `ActorTxSpike.Poll`.
  Do not write `Process.sleep` loops or assert on elapsed time; assert on state.

## 4. Testing by hand: the Test lab

`mix demo --lab`, open the **Test lab** tab. Full description: [architecture/08](architecture/08-test-lab.md).

| You want to test | Do this | Expect |
|---|---|---|
| The router (residency) | Run *India corridor*, *China corridor*; then *Verify logs + residency* | recorded in IN / CN only, never GLOBAL; residency held |
| The router dying | *Kill the router* | money refused while down; supervisor restarts it; a new transaction completes |
| Idempotency | *Submit it twice* with one id; *Send again, same key* after completion | one transaction; the provider's effect counts unchanged |
| Restarts | *Arm* a crash (e.g. `step_succeeded authorise`), submit, *Plan recovery (dry run)*, *Recover now* | the plan says where it stood; paid at most once; never failed over after doubt |
| Audit-log downtime, by state | set the log *Down*, or *Fails after N steps*, and submit | nothing started: fails cleanly; money moved: stops in `needs_review`, never moves more |
| Everything at once | *Run all* | 17 scenarios pass |
| Review | break a provider (settlement fails), send a transaction, open the Review tab | it appears; check, repair (heal), send again, resolve with a name and reason; it moves to resolved; `mix replay reviews` shows it |
| Currencies | Header: From USD, To INR, region IN; then region CN with From INR | priced near 83 via northwind or cobalt; INR in CN is refused with the reason |
| Back-pressure | Bulk send: set the admission limit (e.g. 3), send 12 with no pause | some are *throttled* (refused as overloaded and retried), all 12 complete, the limit panel's peak never exceeds 3 |
| Volume and variety | Bulk send tab: 100 to 10,000 transactions, a pause in ms, *Randomise everything*, optionally a seed | every transaction ends completed, failed (refusals included) or needs review; none are lost; a seed repeats the batch |

The scenario library is also `test/integration/lab_test.exs`, so a regression in any of this fails CI.

### Volume testing by hand

The **Bulk send** tab ([architecture/09](architecture/09-bulk-send.md)) sends 1 to 10,000 transactions with a random amount
between a minimum and maximum and a pause you set. Use *Randomise everything* to cover every corridor,
instrument, customer type and currency pair at once, and a **seed** to repeat a batch exactly when you
find something. Check: sent equals the count you asked for; completed + failed + needs review equals
sent once *in flight* reaches 0; the audit logs still verify (`mix audit.verify`, `mix replay verify`).
Note that randomised batches deliberately include combinations the rules refuse.

## 5. Writing a test

* Put it where the layer says: pure or single-module → `test/unit`; needs a real day, several
  processes or the demo world → `test/integration`; needs a separate OS process → `test/system`.
* A fixed defect gets `@tag :regression` and a name that says what went wrong.
* Prefer the real thing over a mock; the fake provider is the only fake.
* State your arrange steps in the test. Repeating them is fine (duplicate detection is off in `test/`).
* Anything that touches global state (the event server's sinks, the audit router, the admission limit) must be
  `async: false`, and must put the global state back (`on_exit`).
* A test must not depend on timing. If you need to wait, `Wait.until!/2`.
* Keep each test function at cyclomatic complexity 4 or less: extract helpers instead of branching in a test.

## 6. Known flaky or time-sensitive tests

| Test | Why | Mitigation |
|---|---|---|
| `selection_test.exs` "a world of mixed providers" | the speed-led selection compares latencies a millisecond apart, so a heavily loaded machine can reorder them | passes alone every time; it has been seen to fail once in a full parallel run. If it fails, re-run it alone before suspecting a defect. |
| Bulk integration tests (`bulk_test.exs`) | they are `async: false` (named singletons) and run a few hundred real transactions | stable in repeated runs; if one times out, check machine load first |
| `payment_test.exs` "a cut-off that passes after payment but before settlement is escalated" | the cut-off is 525 ms ahead and each provider step takes 150 ms, so a loaded machine can miss the window | passes alone every time; seen once in a full parallel run |
| `provider_resilience_test.exs` "stalled provider is isolated" | its breaker cool-down is 25 ms, so on a loaded machine the breaker can half-open between two calls and the expected `circuit_open` becomes a timeout | re-run alone; it passes. Seen once in a full parallel run |
| `audit_test.exs` "records are made durable together" | asserts the group-commit flag within a 50 ms window | passes alone; seen once in a full parallel run on a loaded machine |
| A one-off cascade of failures from a failed global audit sink | seen twice in full `mix test` runs (once after running `mix test.system` immediately before); never reproduced in 25+ attempts | recorded as unexplained in the engineering story (risk list); if it recurs, keep the first failure's output |

## 7. The Java suites

The Java build ([architecture/13](architecture/13-java-architecture.md)) has its own suites under `java/src/test/java/net/robbrown/actortx/`, run by `./java/jmvn verify`. Measured 2026-10-23: **673 unit tests** (100% lines, 97.2% branches), **68 integration tests** (86.7% lines), mutation score reported in the [quality snapshot](quality/QUALITY_SNAPSHOT.md).

| Layer | Naming | What it is | Gate |
|---|---|---|---|
| Unit | `*Test` (surefire) | one class or a small group; fake providers and stub services; fast and deterministic (injected clocks, sleepers and random sources) | 100% lines, 90% branches (JaCoCo) |
| Integration | `*IT` (failsafe) | real threads, real event bus, real hash-chained audit log in a temp directory, real provider servers with scripted fakes; the whole Spring application over HTTP (`ApiIT`, `PaymentIT`, `SentinelIT`) | at least 85% lines |
| Mutation | PIT profile | all default mutators over the pure core | tracked, not a gate |
| Static | Checkstyle, PMD, CPD, Spotless, `-Werror` | limits as in [DEVELOPER_GUIDE](DEVELOPER_GUIDE.md) section 9 | build fails |

What the notable suites prove:

* `PaymentTest` (unit) and `PaymentResilienceIT`: every payment path: completes, fails over after rejection or error, retries with the same key, waits for a busy provider, reconciles a lost answer instead of repeating it, goes to review rather than to another provider when payment is unresolved, undoes in the right order, refuses to move money when the log cannot be written, and honours cut-offs.
* `WorkflowTest`: the design claim of [ADR 0016](adr/0016-extensible-domain-model.md): an extra step, per-corridor workflows, a provider lacking a capability and an asynchronously confirmed step all work **without changing the engine**. `SelectorCriteriaTest` does the same for a new routing criterion.
* `PaymentIT`: the whole application over HTTP: a payment is recorded on disk and **replays clean against the rules**, jurisdiction refusal, a cut-off that passes, a settlement failure that appears in the review queue and is checked and resolved, a bulk batch that finishes, the admission limit, the event stream, and the dashboard page and the provider fields it reads.
* `ReplayIT`, `AuditIT`, `ReviewWorkflowIT`, `BulkIT`, `MetricsIT`, `EventBusIT`, `GuaranteeWorldIT`, `EconomicsLedgerIT`: each package end to end on real components.

### Java: known time-sensitive tests and traps

| Test or situation | Why | Handling |
|---|---|---|
| `ProviderResilienceIT`, `SelectionWorldIT` | breaker cool-downs and latency ordering are in milliseconds | cool-downs are set to 300 ms and assertions are synchronous; re-run alone if one fails on a loaded machine |
| `PaymentIT` event stream | waits briefly for a client to connect before sending | uses a short sleep; if it flakes, replace with a subscribe-ready signal |
| Running the server while building | `spring-boot:run` loads `target/classes`; a build replaces them and the server breaks with `NoClassDefFoundError` | run the server with `./java/run` (a jar) |
| PMD "unused private method" | a private method used only through a method reference is mis-reported on clean builds | use a lambda |

### Java: what is not covered

* The dashboard's JavaScript has no automated test. A regression test pins the JSON the page reads, and the page was checked by hand in a browser (transactions, review, bulk, performance), but a rendering bug can still pass the build.
* Audit logs written by Java have not been verified by the Elixir verifier (and the reverse).
* Recovery after restart, the CLI tools and the dashboard controls Auto, Pace, Policy and the Test lab are not built in Java, so untested.
* No load results for the Java build yet (`LoadRunner` exists and is tested; no measurement has been published).
* Mutation testing covers the pure core, not the engine's concurrency.

## 8. Not covered (Elixir)

* No load or soak testing; the metrics layer is ready to observe one.
* No branch coverage (not available in the toolchain); mutation testing stands in, sampled.
* Mutation testing covers the pure core, not `Payment`, `Panel` or `Demo`.
* No contract tests against a real provider (none exists yet).
* Use case UC-4 (review workflow) is unbuilt, so untested; settlement refusal and the performance
  view have no system test.
* The dashboard's JavaScript is exercised only by hand and by the system tests' HTTP/SSE checks.

# Developer guide

For someone new to the codebase. How to build it, where things are, the limits the code is held
to, and how to add the common kinds of change. For why things are the way they are, read the
[ADRs](adr/README.md); for how it fits together, [architecture/05](architecture/05-system-structure.md).

## 1. Set up

Elixir 1.18 / OTP 27. A local toolchain can live in `.tooling/` (gitignored):

```sh
export PATH="$PWD/.tooling/elixir/bin:$PWD/.tooling/otp/bin:$PATH"
export MIX_HOME="$PWD/.tooling/mix" HEX_HOME="$PWD/.tooling/hex"   # only if using .tooling/
mix deps.get
mix quality        # format check + Credo strict (incl. the code-shape limits) + unit gate
mix demo --lab     # run it and open http://127.0.0.1:4040
```

There are no runtime dependencies. `credo`, `sobelow` and `mix_audit` are dev/test only.

## 2. The commands you will use

| Command | What it does | Gate |
|---|---|---|
| `mix quality` | `format --check-formatted`, `credo --strict`, `test.unit`: run before every commit | all must pass |
| `mix test.unit` | unit suite (`test/unit`) | 100% line coverage |
| `mix test.integration` | integration suite (`test/integration`) | above 85% line coverage |
| `mix test.regression` | tests tagged `:regression` (one per defect found) | pass |
| `mix test.system` | boots the real system, drives it over HTTP/SSE (`test/system`) | pass |
| `mix test` | everything except system | pass |
| `mix credo --strict` | static analysis and the code-shape limits | no issues |
| `mix compile --warnings-as-errors` | no warnings | clean |
| `python3 scripts/mutate.py` | mutation testing of the pure core (slow, on demand) | all valid mutants killed |
| `mix load --count 2000 --admission none,150,100` | burst measurement: one run per admission limit, prints a table | measurement |
| `mix demo`, `mix demo --lab` | the live world and dashboard (see the [user guide](USER_GUIDE.md)) | n/a |
| `mix audit.verify`, `mix replay ...` | verify and replay audit logs | n/a |

CI (`.github/workflows/ci.yml`) runs: compile with warnings as errors, format check, Credo strict,
dependency audit, Sobelow, unit gate, integration gate, regression pack, system suite.

## 3. Quality limits and how they are enforced

The code is held to these limits. They are lower than the tools' defaults on purpose
([ADR 0012](adr/0012-code-shape-limits.md)): the aim is code a newcomer can read, with decisions
carried by design (clauses, small modules, named predicates) rather than nested `if`s.

| Limit | Value | Applies to | Enforced by |
|---|---|---|---|
| Cyclomatic complexity per function | **4** or less | `lib/`, `test/` | `.credo.exs` → `Credo.Check.Refactor.CyclomaticComplexity` |
| Nesting depth | **2** or less | `lib/`, `test/` | `Credo.Check.Refactor.Nesting` |
| ABC size per function | **40** or less | `lib/` | `Credo.Check.Refactor.ABCSize` |
| Duplicated code | none at mass **16**, 2+ places | `lib/` | `Credo.Check.Design.DuplicatedCode` |
| Unit line coverage | **100%** | everything except the listed tooling | `mix test.unit` (`mix.exs`) |
| Integration line coverage | **above 85%** | everything except `Mix.Tasks.*` | `mix test.integration` |
| Compiler warnings | **0** | all | `--warnings-as-errors` |
| Formatting | `mix format` clean | all | `mix format --check-formatted` |
| Runtime dependencies | **0** | | review |

What counts as a decision for cyclomatic complexity: each `if`/`unless`, each `case`/`cond`/`with`
clause beyond the first, each `&&`, `||`, `and`, `or`, and each `for` filter. A function with
several *clauses* is measured per clause, which is why pattern-matching heads are the preferred
shape.

Coverage exclusions are explicit and short (`mix.exs`): `Demo`, `Demo.Handler`, `Demo.Lab`,
`Demo.Lab.Scenarios` are excluded from the *unit* gate only (they are exercised by the integration
suite, including the lab's 17 scenarios); `Mix.Tasks.Demo`, `Mix.Tasks.Audit.Verify`,
`Mix.Tasks.Replay` (thin entry points) and `ActorTxSpike.Wait` (a test helper) are never measured.

### Staying inside the limits

When Credo says a function is too complex, reshape it; do not raise the limit or disable the check.

| Symptom | Reshape |
|---|---|
| `case`/`cond` with 3+ branches in a body | several function clauses with patterns and guards |
| A condition with `and`/`or` | a named predicate function (`unresolved_money_doubt?/1`) |
| `x && x.field`, `x \|\| default` | a clause for `nil` (`defp field(nil, _), do: nil`) |
| A long procedure with side effects | a pipeline of small named steps; move the story-telling out |
| The same parse/validate loop in two modules | the shared one (`Validation.parse_fields/4`, `Validation.build/2`) |
| A big literal map or list of entries | split by theme (`Scenarios.routing/0`, `restarts/0`) or make it data folded by one function |
| Polling with `Process.sleep` | `Poll.until/2` (code) or `Wait.until!/2` (tests) |
| Deciding and doing mixed | split: pure decision function, then a thin function that acts on it |

Worked examples in the code: `Selector.select/4` (pipeline) and `Selector.Narrator` (the story
split out), `Eligibility.Parser` (parsing split out of deciding), `Recovery.stood_at/2` (clauses
instead of `case`), `Payment.run_step/3` (clauses instead of `cond`).

## 4. Layout

```
lib/actor_tx_spike.ex            public API: submit, await, snapshot
lib/actor_tx_spike/              core modules (see architecture/05 for the map)
  payment.ex                       one process per transaction: lifecycle, retries, compensation
  selector.ex, selector/narrator.ex  quoting and the pure judgement; the story of a selection
  policy.ex, policy_set.ex         routing policy as validated data; global/merchant/customer cascade
  eligibility.ex, eligibility/     jurisdiction rules (decide) and their parser
  validation.ex                    shared "plain data to struct" validation
  audit.ex, audit/                 hash-chained log, one-writer lock, regional router
  recovery.ex, replay.ex, replay/  recover from the log; replay, verify, what-if, compare, residency
  provider/                        adapter behaviour, health model, server (breaker), fake
  money.ex, costs.ex, economics.ex exact money; the cost and savings ledger
  metrics.ex, metrics/             sampler and rollups
  demo.ex, demo/                   the demo world, the Test lab and Bulk send (tooling)
  demo/bulk.ex, demo/bulk/plan.ex  batch sender (server, backs off when refused) and its pure plan/generator
  review.ex, review/queue.ex       the review workflow (server) and its pure queue
  admission.ex                     admission control: the limit on transactions in flight (back-pressure)
  panel.ex                         HTTP + SSE dashboard server
lib/mix/tasks/                   mix demo, audit.verify, replay
priv/panel.html                  the dashboard (single file)
test/unit, integration, system   the three suites; test/support holds test-only helpers
config/                          dev, test, prod (audit required), runtime (env vars)
scripts/mutate.py                mutation testing
docs/                            this documentation
```

## 5. Conventions that matter

* **Money is integers.** Amounts and fees are integer minor units; rates are integers scaled by
  1e8; rounding is half-even; costs are pico-units. Parse decimals at the edge with `Money`.
  Never use floats for money. Display with `Money.format/1`.
* **Every provider call is idempotent and write-ahead.** Calls carry stable keys
  (`<tx>:<provider>:<step>`); `Payment` waits on `Events.barrier/0` before a money-moving call. If
  the audit log cannot make the record durable, the call is not made.
* **Never fail over after doubt.** An unresolved pay or settle goes to `needs_review`; it is never
  retried at another provider.
* **Decisions are pure where possible** (`Policy`, `Selector.evaluate/3`, `Eligibility`,
  `Recovery.plan/1`, `Provider.Health`), so replay can reuse the same code as the live path.
* **Narrate everything through `Events`;** record replay inputs with `Journal` (hidden from the
  dashboard, kept in the audit log). Do not log card numbers: `Audit` scrubs free text, but do not rely on it.
* **No `String.to_atom`, `binary_to_term`, `Code.eval*` on external data.**
* Comments say why, not what. Keep the style of the surrounding code.

## 6. Recipes

**Add a provider adapter.** Implement `ActorTxSpike.Provider` (quote, accept, authorise, pay,
settle, the three undo operations, status, probe). Start a `Provider.Server` around it. Add
contract tests with the same call sequences `Provider.Fake` is tested with.

**Add a replay rule.** Add a private function `rule(id, records)` (or with the transaction's summary as a third argument) to `Replay.Rules` and call it from `check_tx/3`, add a unit test in `test/unit/replay_test.exs` that breaks a good day in exactly
one way and expects exactly that violation (see the existing ones), and add it to the rule list in
the [Test lab](architecture/08-test-lab.md) and user guide if people should see it.

**Add a jurisdiction rule.** Edit the data given to `Eligibility.new/1` (the demo's are in
`Demo.eligibility/0`). Name every log the rule uses in `Demo.log_names/0` and, in production, in
`ACTOR_TX_AUDIT_LOGS`. Add an `eligibility_test.exs` case and a scenario in the lab if the
behaviour is user-visible.

**Add a lab scenario.** In `Demo.Lab.Scenarios`, add an entry to the matching theme function
(`routing/0`, `idempotency/0`, `provider_faults/0`, `audit_outage/0`, `restarts/0`) and a function
that runs it and calls `check/3`. Each scenario must make at least two checks. The integration
test `lab_test.exs` runs them all.

**Add a randomised setting to Bulk send.** Add its name and the values it draws from to `choices` in
`Demo.Bulk.Plan` (and to `@draw_order`, after any setting it depends on), map the JSON key to the request field in
`Demo`'s `@submit_fields`, add a tick box in `BULK_FIELDS` in `priv/panel.html`, and extend `bulk_plan_test.exs`
(every drawn value must be in the choices; a seeded plan must repeat).

**Add a review action.** Emit the intent as an event, `Events.barrier` it, only then act (`Review.durably/5`), record the result, and fold the new
record kind in `Review.Queue.apply_record/2` so the queue rebuilds from the log. Make the audit router keep routing the transaction (it already does until
`review_resolved`) and cover the rebuild in a test.

**Add a currency or a carrying provider.** A currency: add its US-dollar value to the demo's price list (`Demo`'s `@usd_per_unit`) and to
`Plan.choices/0`. A provider's currencies: its `currencies:` list in `Demo`'s `@providers`. A region's currencies: the rule's `currencies`.

**Add a back-pressure signal.** A refusal must mean *nothing was done*: return `{:error, {:overloaded, retry_after_ms}}` (or a
provider's `{:error, :overloaded}`) before any side effect or record, so callers can safely wait and retry. Never reuse
`:overloaded` for an answer that may have reached the provider (that is `:timeout` / `:in_doubt`, and is reconciled, not retried).

**Add a policy field.** Add it to `Policy` (`@keys`, struct, `fields/0`, a `parse_*` function), to
`Tx.policy/2` so replay rebuilds it, and to `Journal` enrichment of `tx_received`. Unit-test the
parser including the error message.

**Change what is recorded.** The audit log is the replay script. Adding a field is safe; renaming
or removing one breaks `Replay`/`Recovery` for existing logs. Add a replay test over a recorded day.

## 7. Debugging

* `mix demo --lab` and the Test lab's scenarios reproduce most situations with the right fault.
* Follow one transaction: click it on the Live tab, or `mix replay txs LOG` then `mix replay at LOG --n N`.
* "Why did it pick that provider?": `mix replay verify LOG` re-runs the live decision code over
  the recorded quotes and reports any difference.
* A unit test that fails only sometimes is almost always a hand-rolled sleep. Use
  `Wait.until!/2` (tests) or `Poll.until/2` (code) and assert on state, not time.
* The audit log refusing to start usually means a second writer (see `<log>.lock`) or a broken
  chain (`mix audit.verify`).

## 8. Known limits to be aware of

See [ROADMAP](ROADMAP.md) and the risk list in [ENGINEERING_STORY](ENGINEERING_STORY.md): retention
is recorded not enforced; no forward deal lifecycle, sanctions/KYC gates or trade reporting; the
jurisdiction matrix is unverified by counsel; the dashboard has no authentication (loopback only).

# ADR 0012: Code-shape limits (complexity, nesting, size, duplication)

Status: accepted · Date: 2026-10-20

## Context
The codebase is meant to be picked up by people new to it, and to be changed safely in a
domain (money movement) where a misread branch costs real money. Measured on 2026-10-20 the
code already passed Credo's defaults (cyclomatic complexity 9, no duplicate blocks), but the
worst functions were still hard to read: a 9-branch `summarize`, a `resolve` of nested
`case`/`if`, a 20-line `cond` with `and`/`or` in its conditions.

The owner's steer: lower the thresholds, because nested `if`s are better handled by design
patterns, good interfaces and decent abstractions, and those reduce cyclomatic complexity as a
side effect. Complexity limits are a *forcing function* for that design, not an end in themselves.

## Decision
Four limits, enforced by `mix credo --strict` (configured in [`.credo.exs`](../../.credo.exs), run
in CI and by `mix quality`):

| Limit | Value | Scope | Credo check |
|---|---|---|---|
| Cyclomatic complexity per function | **at most 4** (default was 9) | `lib/` and `test/` | `Refactor.CyclomaticComplexity` |
| Nesting depth | **at most 2** | `lib/` and `test/` | `Refactor.Nesting` |
| ABC size per function | **at most 40** | `lib/` only | `Refactor.ABCSize` |
| Duplicated code | **mass 16** (about 16 AST nodes) in 2 or more places (default was 40) | `lib/` only | `Design.DuplicatedCode` |

Together with the existing gates: unit coverage 100%, integration coverage above 85%,
`--warnings-as-errors`, `mix format --check-formatted`.

ABC size and duplication are not applied to `test/`: a test is a script that should read top to
bottom with its set-up visible, and repeating arrange steps there is deliberate. Cyclomatic
complexity and nesting do apply to tests, because a polling loop with a `cond` is exactly the
kind of thing that should be one shared helper (`ActorTxSpike.Wait`, `ActorTxSpike.Poll`).

The limits are not raised to make a change pass. If a function does not fit, it needs a better
shape; the patterns below are what the existing code uses.

## How code is shaped to fit
* **Decide in function heads, not in bodies.** Several clauses with patterns and guards instead
  of `case`/`cond` inside a function (`Payment.run_step/3`, `Recovery.stood_at/2`,
  `Selector.outcome/2`, `Fake.effect_result/3`).
* **One concern per module.** Deciding, narrating and parsing are separate:
  `Selector` (pure `evaluate/3`) and `Selector.Narrator` (events and journal);
  `Eligibility` (decide) and `Eligibility.Parser` (validate data).
* **Table-driven instead of branchy.** A list of `{name, key, parse}` fields walked by one
  function (`Validation.parse_fields/4`); a map from terminal kind to outcome
  (`Replay.Tx`); `{output, series}` specs folded by reducer (`Metrics.Rollup.finalize/1`).
* **Name the predicate.** A condition with `and`/`or` becomes a function whose name says what
  it means (`Replay.Rules.unresolved_money_doubt?/1`, `Policy.too_slow?/2`).
* **Optional things get a clause.** `nil`-tolerant reads are `defp field(nil, _), do: nil`,
  not `x && x[...]` (each `&&`, `||`, `and`, `or` counts as a branch).
* **Pipeline of small named steps** for a procedure (`Selector.select/4`: partition, observe,
  evaluate, build, narrate).
* **Waiting is one function** (`Poll.until/2`), never a hand-rolled `cond` with `Process.sleep`.

## Consequences
* Reading a function never needs more than four decisions held in the head.
* More, smaller functions and a few more modules (`Validation`, `Parser`, `Narrator`, `Poll`).
  Each is covered to 100% by the unit gate.
* A refactor to meet the limits touched 40 files in one change; it was safe because the unit
  gate, the 17-scenario lab and the crash/recover tests held. Mutation testing was re-run on the
  files that changed shape (see [QUALITY_SNAPSHOT](../quality/QUALITY_SNAPSHOT.md)).
* New code that exceeds a limit fails CI. Whole-file escapes (`credo:disable-for-this-file`) are
  not used and should not be added.
* The limits measure shape, not correctness. They do not replace review or the invariant tests.

## Alternatives considered
* **Keep Credo's defaults** (complexity 9): passes today but permits the functions that were hardest to read.
* **Complexity 3**: 70 functions in `lib/` and `test/` would need splitting, mostly dispatch tables that are clearer as one function with several clauses; the gain stops paying for the fragmentation. 4 is the lowest value where a plain `case` with a few clauses still fits.
* **A dedicated complexity tool outside Credo**: more tooling for the same signal.

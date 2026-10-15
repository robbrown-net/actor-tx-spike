# ADR 0005: Test strategy and quality gates

Status: accepted · Date: 2026-10-15

## Context
Owner requirement: 100% automated unit-test coverage and >85% automated integration-test coverage.

## Decision
* `test/unit` and `test/integration` are separate suites with separate gates:
  `mix test.unit` (100% line coverage) and `mix test.integration` (>85%).
* Decision logic is kept **pure** (`Policy`, `Health`) so it is cheap to test exhaustively.
* A fake provider is **scriptable at runtime** (modes, mode lists, latency, drift) and records every effect.
* A **randomised fault-storm** test asserts money-safety invariants (never paid twice, failed never paid, failovers fully undone).
* Tooling modules (`Demo`, `Demo.Handler`) are excluded from the *unit* gate only; the thin `Mix.Tasks.Demo` entry point is excluded everywhere. Both exclusions are explicit in `mix.exs`.
* Static analysis: Credo (strict), Sobelow, mix_audit as dev-only dependencies.

## Additional layers (added 2026-10-15)
* **System suite** (`test/system`, `mix test.system`): boots the real `mix demo` as an OS process and drives it only over HTTP/SSE.
* **Regression pack** (`mix test.regression`): tests tagged `:regression`, one per defect found.
* **Mutation testing** (`scripts/mutate.py`): applies small changes to the pure core and checks the tests notice.
  It exposed boundary and message gaps that 100% line coverage hid; sampled mutants are now all killed.
  (`muzak` was tried first and is incompatible with Elixir 1.18's ExUnit.)
* **CI** (`.github/workflows/ci.yml`): format, Credo strict, audits, unit/integration gates, regression, system.

## Known limits
* Coverage is **line-based**; Erlang's `cover` has no branch coverage. Mutation testing is the stand-in and is run on demand over a sample, not exhaustively.
* The system suite runs the SUT in another OS process, so it has no line coverage; scenario coverage against the use cases is tracked instead.
* Randomised tests use a fixed seed per run but concurrency makes interleavings vary.

## Consequences
* Refactors are safe; gates fail loudly.
* 100% line coverage is necessary, not sufficient: the invariant tests carry the real weight.

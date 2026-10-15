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

## Known limits
* Coverage is **line-based**; branch/mutation measurement is still to do.
* No system-test suite (boot the real demo, drive over HTTP/SSE) and no tagged regression pack yet.
* Randomised tests use a fixed seed per run but concurrency makes interleavings vary.

## Consequences
* Refactors are safe; gates fail loudly.
* 100% line coverage is necessary, not sufficient: the invariant tests carry the real weight.

# ADR 0015: A Java 21 / Spring Boot port, in chunks, held to the same bars

Status: accepted · Date: 2026-10-23

## Context
The Elixir/OTP spike did its job: it proved the design (orchestration, idempotent steps, write-ahead evidence, replay, review, back-pressure) and taught the owner the BEAM. A production system for a payments business is likely to be built by and with teams fluent in Java and Spring, with that ecosystem's operations tooling (Actuator and Micrometer for metrics and health, Spring Security, Spring Data, mature CI and static analysis). The owner asked for the application to be converted "where there is value", in chunks that each add end-to-end capability and quality, keeping the same complexity and duplication limits, 100% unit coverage and at least 85% integration coverage, with test suites at least as comprehensive.

## Decision
* Build the port in `java/` beside the Elixir code (one repository, one history): Java 21, Spring Boot 3, Maven. The Elixir implementation remains the reference and keeps its CI until the port reaches parity.
* **Convert everything that carries the business rules** (money, policy, jurisdiction, providers and their health, selection, then payment, audit, recovery, replay, review, back-pressure, bulk and load), because those are the assets that matter and every one has tests to port. **Replace the hand-rolled infrastructure with the framework** where Spring is better: the HTTP and SSE server (Spring MVC), configuration, health and metrics (Actuator), scheduling (the sentinel), JSON. Keep the dashboard UI (`priv/panel.html`) unchanged by speaking the same JSON.
* **Idiom, not transliteration.** Records and sealed interfaces for the closed vocabularies (`Answer`, `Validated`, `Outcome`, `Item`); immutability for the pure model (`Health`, `Policy`); virtual threads for blocking work with enforced timeouts (the counterpart of a process per call); small methods and polymorphism instead of branching, which the limits force.
* **The same bars, enforced in the build** (`./jmvn verify` and CI): Checkstyle cyclomatic complexity 4, nesting 2, method length 30; PMD copy-paste detection at 50 tokens; JaCoCo 100% line and 90% branch coverage for unit tests, and at least 85% line coverage for integration tests; Spotless formatting; warnings as errors; PIT mutation testing of the pure core. Limits apply to test code as well.
* **Chunks**, each a working vertical slice with its tests: 1 quote and select over HTTP; 2 pay (events, orchestrator, audit log, admission, economics, SSE); 3 operate (recovery, replay, review, bulk and load, dashboard, CLI).
* **Subagents for bounded ports.** Pure, well-specified modules with existing test suites were delegated to smaller models in isolated worktrees, each required to pass the full gates before returning; the work was then reviewed, merged and integrated.

## Consequences
* The port is held to a stricter yardstick than the original in one respect: branch coverage and full-mutator mutation testing exist on the Java side and not on the Elixir side.
* Spring and the test stack are real dependencies; the Elixir side has none at runtime. That trade is deliberate and confined to `java/`.
* Two implementations live side by side for a while. Defects found by the port are fixed in both (see the list in `java/README.md`).
* The BEAM's supervision and per-process isolation are replaced by explicit lifecycle management and thread discipline: worth re-evaluating (resilience testing, `chaos` scenarios) as chunk 2 lands.

## Alternatives considered
* **Rewrite from scratch in Java.** Throws away the tested business rules.
* **Keep Elixir only.** Fine technically; does not meet the stated ecosystem goal.
* **Port module by module with no vertical slices.** Hides integration problems until late.

## Outcome (2026-10-23)

Chunks 1 to 3 are largely done (quote and select, pay with the write-ahead audit log, replay, review, bulk send, metrics and the dashboard served by Spring) with the bars held: 673 unit tests, 68 integration tests, mutation score 98% on the pure core. Recovery after restart, the CLI tools and four dashboard controls remain. The engine was reshaped by [ADR 0016](0016-extensible-domain-model.md) so that new steps, workflows and criteria need no engine change. Lessons: a server run from `target/classes` breaks if a build runs beside it (use `./java/run`); the dashboard page is an implicit contract with the JSON the server sends and needs its own test; PMD mis-reports private methods used only through method references.

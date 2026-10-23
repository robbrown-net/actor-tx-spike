# Actor TX: Java and Spring

The Java 21 / Spring Boot 3 port of the Elixir spike in the repository root. Same system (orchestrated FX swap and payment across unreliable providers), same quality bars, converted in chunks that each add an end-to-end capability. The Elixir implementation remains the reference until the port reaches parity ([ADR 0015](../docs/adr/0015-java-spring-port.md)).

## Run it

```sh
cd java
./jmvn verify                 # everything: format, static analysis, unit tests + gate, integration tests + gate
./run                         # build a jar and run it on http://127.0.0.1:4040 (loopback only); extra args go to the app
./jmvn spring-boot:run        # the same from the classes (do NOT build in another terminal while it runs)
./jmvn spotless:apply         # fix formatting
```

**Run the server with `./run`, not `spring-boot:run`, if you will also build or test while it is up.** `spring-boot:run` loads classes straight from `target/classes`, so any `./jmvn verify` or `clean` run in another terminal replaces them under the live JVM and it fails with `NoClassDefFoundError` (for example in the sentinel). A jar is not affected. Options: `--actortx.audit.dir=DIR` (write the audit logs), `--actortx.demo.pace=0.3` (provider speed).

`./jmvn` is Maven with the JDK 21 and Maven 3.9 vendored in `../.tooling` (no installation; works from git worktrees).

## What is converted so far

| Chunk | Capability, end to end | Status |
|---|---|---|
| 1 | **Quote and select** over HTTP: exact money, policy and cascade, jurisdiction rules, provider model (scriptable fake with currency pairs, health and circuit breaker, bulkhead with priority classes), concurrent selector, sentinel, operator control | done |
| 2 | **Pay**: event bus, payment orchestrator (idempotent steps, retries, failover, compensation, doubt resolved by asking the provider), hash-chained audit log and regional router with write-ahead barrier, admission control (429 + `Retry-After`). Economics, guarantees and cut-offs are wired in | done |
| 3 | **Operate**: replay (`GET /api/replay`), review workflow, bulk send, metrics (JVM figures) and the existing dashboard (`/`) served by Spring. Still to do: recovery after restart, CLI tools, dashboard actions `auto`/`pace`/`policy`/lab | mostly done |
| design | Steps, workflows, provider capabilities and routing criteria are values ([ADR 0016](../docs/adr/0016-extensible-domain-model.md)); one shared `Outcome`; named event kinds with a schema version | done |

**Measured 2026-10-23:** 673 unit tests (100% lines, 97.2% branches), 68 integration tests (86.7% lines), PIT 401 of 409 killed. Structure, diagrams, extension points: [../docs/architecture/13-java-architecture.md](../docs/architecture/13-java-architecture.md).

### The HTTP API (JSON, snake_case, the same shapes the Elixir dashboard speaks)

| Method and path | What it does |
|---|---|
| `GET /api/providers` | every provider and how it looks now (health, breaker, in flight, rate, currencies) |
| `POST /api/select` | quote and select: `{amount, source_currency, destination_currency, merchant_id, customer_id, policy, deadline_ms, baseline}` returns the ranking, the exclusions with reasons and which policy layers applied |
| `POST /api/eligibility` | what jurisdiction allows a request (`originRegion`, `destinationRegion`, `instrument`, `customerType`, currencies) |
| `POST /api/control` | `{action: "provider", id, do}`: slow, stall, error, reject, authorise_fails, pay_answer_lost, pay_vanishes, settle_fails, settle_vanishes, drift, release, reprice, heal |
| `POST /api/payments` | send a payment: `{amount, merchant_id, customer_id, origin_region, destination_region, instrument, customer_type, source_currency, destination_currency, baseline}`; 202 `{ok, id}`, 422 invalid, 429 busy |
| `GET /api/payments/{id}` | the transaction as it stands: phase, provider, steps done, history, outcome |
| `GET /api/payments/{id}/result?timeout_ms=` | wait for it to end (200, or 408) |
| `GET /api/replay` | the audit log checked against the rules and residency (needs `actortx.audit.dir`) |
| `GET /api/review`, `GET /api/bulk`, `GET /api/metrics`, `GET /api/snapshot` | what the dashboard polls (review queue, bulk progress, metrics, the whole picture) |
| `GET /api/events`, `GET /api/stream`, `GET /events` | recent events; named server-sent events; the unnamed stream the page listens to |
| `GET /api/economics`, `/api/guarantees`, `/api/cutoffs` | cost and benefit, guarantee ledger, cut-offs |
| `POST /api/control` | actions `provider`, `cutoff`, `submit`, `admission`, `review` (`do`: check, retry, resolve), `bulk` (`do`: start, stop, clear); `auto`, `pace`, `policy`, `lab` answer "not available in the Java port yet" |
| `GET /` | the dashboard (`priv/panel.html`, shared with the Elixir build) |
| `GET /actuator/health` | liveness |

Errors are `{ok: false, error, errors}` with 422 (invalid input), 429 (at the admission limit) or 400 (not JSON). Set `actortx.audit.dir` to write the hash-chained audit logs (one per region plus `GLOBAL`); without it nothing is durable and the write-ahead barrier is trivially satisfied.

## The quality bars (the same as the Elixir side)

| Bar | Elixir | Java |
|---|---|---|
| Cyclomatic complexity per method | Credo 4 | Checkstyle `CyclomaticComplexity` **4** |
| Nesting depth | Credo 2 | Checkstyle `NestedIfDepth`/`NestedForDepth` **2**, `NestedTryDepth` 1 |
| Method size | Credo ABC 40 | Checkstyle `MethodLength` **30** lines, `NPathComplexity` 16, `BooleanExpressionComplexity` 3, parameters 6; PMD cognitive complexity 6 |
| Duplication | Credo mass 16 | PMD CPD, **50 tokens** (main code) |
| Unit coverage | 100% lines | JaCoCo **100% lines**, and **90% branches** (the Elixir toolchain could not measure branches) |
| Integration coverage | above 85% | JaCoCo **at least 85% lines** |
| Formatting, warnings | `mix format`, warnings as errors | Spotless (google-java-format), `-Xlint:all -Werror` |
| Mutation testing | `scripts/mutate.py` (sampled) | PIT, all default mutators over the pure core: `./jmvn -Pmutation test-compile org.pitest:pitest-maven:mutationCoverage` |

All of it runs in `./jmvn verify` and in CI (`.github/workflows/java.yml`). Limits apply to test code too; the gate configuration is in `pom.xml`, `config/checkstyle.xml`, `config/pmd.xml`. Coverage exclusions are explicit and short: the application entry point and `tooling/` (the demo world, its wiring and presets).

## Layout

```
src/main/java/net/robbrown/actortx/
  money/        exact Money (long minor units, rates scaled 10^8, half-even)
  validation/   Validated, FieldReader, Parsers: plain data to validated records, every error at once
  policy/       Policy, PolicySet (global > merchant > customer cascade), criteria/ (pluggable routing criteria)
  eligibility/  jurisdiction rules: instruments, customer types, currencies, providers, logs, retention
  provider/     Operation, ProviderAdapter (perform, supports), FakeProvider, Health (breaker), ProviderServer (bulkheads, timeouts), Limits
  selection/    Selector (pure judgement), QuoteSelector (fan-out, narration), Participant, Selection
  payment/      Payment (the engine: one virtual thread per transaction, runs a Workflow of Steps), Step, Steps, Workflow, Workflows, Phase, Outcome, Result, journal
  events/       EventBus (ordered narrative, durable sinks, barrier), EventKinds, Event, DurableSink
  audit/        AuditLog (hash chain), AuditRouter (regional logs, residency), one-writer lock, card scrubber
  economics/    costs and the economics ledger
  guarantee/    guarantees, ledger, regional cut-offs
  metrics/      rollups, JVM figures;  narrative/  story lines
  review/       review queue, check, retry, resolve
  replay/       load, verify (rules), state at a moment, what-if, compare, residency
  bulk/         BulkRunner, LoadRunner, plans, the submitter over the payment service
  service/      PaymentService, Admission, SnapshotService, ControlActions, ReplayService, Sentinel, catalog and control
  api/          REST controllers, SSE, the dashboard page, error handling
  tooling/      the demo world and its Spring wiring (excluded from the coverage gates)
src/main/resources/demo/   the demo's policy cascade and jurisdiction rules, as data
src/test/java/             *Test (unit), *IT (integration, failsafe)
```

## From Elixir to Java

| Elixir | Java |
|---|---|
| `Money` (integers) | `money.Money` (`long`, `BigDecimal` at the edge) |
| `Policy`, `PolicySet` | `policy.Policy` (record), `policy.PolicySet`; validation through `FieldReader` |
| `Eligibility` (+ `Parser`, `Rule`) | `eligibility.*`; unknown instrument or customer type is refused (fail closed) rather than defaulted |
| `Provider` behaviour, `Provider.Fake` | `provider.ProviderAdapter`, `provider.FakeProvider` (modes, scripts, currency pairs) |
| `Provider.Health` | `provider.Health` (immutable) |
| `Provider.Server` (GenServer) | `provider.ProviderServer` (virtual-thread call per request, lock-guarded health, priority bulkhead) |
| `Selector`, `Selector.Narrator` | `selection.Selector` (pure), `QuoteSelector`, `QuoteFanOut`, `Narrator` |
| process per transaction (OTP) | a virtual thread per transaction running a workflow of steps |
| `Audit`, `Audit.Router`, `Events` | `audit.*`, `events.EventBus` |
| `Replay.*`, `Review`, `Demo.Bulk`, `Metrics` | `replay.*`, `review.*`, `bulk.*`, `metrics.*` |
| `Panel` (hand-rolled HTTP) | Spring MVC controllers, Actuator |

Behavioural notes where Java differs on purpose: a present but unknown instrument or customer type is refused with a reason; a non-map `regions` is reported, not crashed on; `PolicySet` combinations that are invalid only as a whole raise an error (the Elixir version crashes with a `MatchError`: a latent defect to fix on that side too).

# Documentation index

| Document | What it is |
|---|---|
| [../java/README.md](../java/README.md) | The Java 21 / Spring Boot build: how to run it, what is converted, the API, the quality bars, layout |
| [architecture/13-java-architecture.md](architecture/13-java-architecture.md) | **Java build structure**: packages, the step/workflow engine, state machine, sequence, extension points, compatibility with Elixir, mapping |
| [USER_GUIDE.md](USER_GUIDE.md) | Running and watching the system: dashboard, breaking providers, the Test lab, the audit log, replay, recovery, production settings |
| [DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md) | Set-up, commands, **quality limits and how they are enforced**, layout, conventions, recipes |
| [TESTING.md](TESTING.md) | Test layers, gates and thresholds, testing by hand in the lab, writing tests, known flaky tests, what is not covered |
| [REQUIREMENTS.md](REQUIREMENTS.md) | Functional and non-functional requirements, each with where it is verified and its status |
| [ENGINEERING_STORY.md](ENGINEERING_STORY.md) | The full story: start, decisions, steers, open questions, risks, roadmap |
| [ROADMAP.md](ROADMAP.md) | Phase status and known gaps |
| [adr/](adr/README.md) | Architecture decision records |
| [architecture/01-use-cases.md](architecture/01-use-cases.md) | Cockburn fully-dressed use cases |
| [architecture/02-domain-model.md](architecture/02-domain-model.md) | Domain model, glossary, operation contracts (Larman) |
| [architecture/03-system-sequence-diagrams.md](architecture/03-system-sequence-diagrams.md) | System sequence diagrams (Larman) |
| [architecture/04-design-sequences-and-activities.md](architecture/04-design-sequences-and-activities.md) | Design sequences, activity and state machines |
| [architecture/05-system-structure.md](architecture/05-system-structure.md) | Components, supervision, deployment |
| [architecture/06-data-model.md](architecture/06-data-model.md) | Entity-relationship model and invariants |
| [architecture/07-observability-and-performance.md](architecture/07-observability-and-performance.md) | Metrics and monitoring design |
| [compliance/COMPLIANCE_EVIDENCE.md](compliance/COMPLIANCE_EVIDENCE.md) | PCI DSS and Dodd-Frank: what evidence exists, what is missing |
| [adr/0016-extensible-domain-model.md](adr/0016-extensible-domain-model.md) | Steps, workflows, capabilities and criteria are values, not enum members |
| [architecture/11-review-workflow.md](architecture/11-review-workflow.md) | Review workflow: queue, check, retry, resolve, write-ahead, rebuilt from the log |
| [architecture/12-currencies.md](architecture/12-currencies.md) | Currencies: pricing by pair, provider capability, regional currency rules |
| [architecture/10-back-pressure.md](architecture/10-back-pressure.md) | Back-pressure: admission limit, bulkhead priority classes, waiting instead of failing |
| [quality/LOAD_RESULTS.md](quality/LOAD_RESULTS.md) | Load results: completion rate of a burst per admission limit, method, caveats |
| [architecture/09-bulk-send.md](architecture/09-bulk-send.md) | Bulk send: many transactions, random amounts, randomised settings, a pause, progress |
| [architecture/08-test-lab.md](architecture/08-test-lab.md) | The Design for Test lab: provoking and judging restarts, idempotency, the router and audit-log downtime |
| [quality/QUALITY_SNAPSHOT.md](quality/QUALITY_SNAPSHOT.md) | Coverage, SLOC, complexity, duplication, security |
| [report/engineering-report.html](report/engineering-report.html) | Single-page report combining all of the above |

Diagrams are Mermaid and render on GitHub. The HTML report renders them in the browser.

Performance measurements and decisions (throughput, CPU, memory, audit-log cost) are in [../Performance Analysis/README.md](<../Performance Analysis/README.md>).

# Architecture decision records

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-orchestrated-transactions.md) | Orchestration over choreography; declarative flows; availability-first; amendments 1–3 | accepted |
| [0002](0002-commit-step-safety-rules.md) | Commit-step safety: idempotency keys, doubt handling, undo operations | accepted |
| [0003](0003-providers-are-modelled-not-trusted.md) | Breaker, health model, drift quarantine, gradual recovery | accepted |
| [0004](0004-observability-as-a-first-class-requirement.md) | Single narrative stream; dashboard as projection and control surface | accepted |
| [0005](0005-test-strategy-and-quality-gates.md) | Unit 100% / integration >85% gates; invariant testing | accepted |
| [0006](0006-toolchain-and-dependencies.md) | Local toolchain, no Homebrew, zero runtime dependencies | accepted |
| [0007](0007-cascading-policy-guarantees-and-deadlines.md) | Cascading policy, standing guarantees, cut-offs, settlement | accepted |
| [0008](0008-exact-money-audit-chain-and-economics.md) | Integer money, hash-chained audit log, cost and savings ledger | accepted |
| [0010](0010-write-ahead-log-and-crash-recovery.md) | Write-ahead barrier, fail closed, one writer per log, recovery from the log | accepted |
| [0011](0011-jurisdiction-eligibility-and-regional-logs.md) | Jurisdiction as a hard constraint before scoring; regional logs; provable residency | accepted |
| [0009](0009-replay-the-day.md) | Journal in the audit log; replay: state at a moment, rule verification, comparison, what-if | accepted |
| [0012](0012-code-shape-limits.md) | Code-shape limits: cyclomatic complexity 4, nesting 2, ABC 40, duplication mass 16 | accepted |
| [0013](0013-back-pressure.md) | Back-pressure: admission control, priority bulkheads, waiting instead of failing | accepted |
| [0014](0014-product-depth-currencies-review-and-batches.md) | Currencies price, route and permit; a review workflow for needs_review; recorded batches | accepted |
| [0015](0015-java-spring-port.md) | Java 21 / Spring Boot port in chunks, held to the same bars | accepted |
| [0016](0016-extensible-domain-model.md) | Steps, workflows, capabilities and criteria are values, not enum members | accepted |
| [0017](0017-payment-process-reaping.md) | Finished payments stop in batches; their final views stay answerable from a bounded store | accepted |
| [0018](0018-audit-group-commit.md) | Audit barrier as group commit: one sync thread forces for many payments; a force covers records written before it; a failed force fails every waiter | accepted |

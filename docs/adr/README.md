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

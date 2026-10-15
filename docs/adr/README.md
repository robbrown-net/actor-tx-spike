# Architecture decision records

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-orchestrated-transactions.md) | Orchestration over choreography; declarative flows; availability-first; amendments 1–3 | accepted |
| [0002](0002-commit-step-safety-rules.md) | Commit-step safety: idempotency keys, doubt handling, undo operations | accepted |
| [0003](0003-providers-are-modelled-not-trusted.md) | Breaker, health model, drift quarantine, gradual recovery | accepted |
| [0004](0004-observability-as-a-first-class-requirement.md) | Single narrative stream; dashboard as projection and control surface | accepted |
| [0005](0005-test-strategy-and-quality-gates.md) | Unit 100% / integration >85% gates; invariant testing | accepted |
| [0006](0006-toolchain-and-dependencies.md) | Local toolchain, no Homebrew, zero runtime dependencies | accepted |

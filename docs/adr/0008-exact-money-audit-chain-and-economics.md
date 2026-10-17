# ADR 0008: Exact money, a tamper-evident audit chain, and an economics ledger

Status: accepted (2026-10-17)

## Context

Three needs arrived together. A payments system must not hold money in floats. Auditors
(PCI DSS requirement 10, financial recordkeeping) expect a complete, ordered, tamper-evident
record. And the business wants to know what the system costs to run and what better rates
save or earn.

## Decisions

### 1. Money is integers (`ActorTxSpike.Money`)
Amounts and fees are **integer minor units**; rates are **integers scaled by 10^8**.
Decimal input (literals, strings) is converted exactly at the edge through its shortest
representation, never through binary floating point. Rounding is **half-even**. Display
helpers exist for narration and the dashboard; nothing computes with them.
Floats remain only for non-money ratios (scores, basis-point comparisons, error rates).

### 2. The audit log is an append-only hash chain (`ActorTxSpike.Audit`)
Every event becomes one JSON line committing to the previous record's hash. Editing, removing,
inserting or reordering is detected at the first affected line (`mix audit.verify`). The log
refuses to start on a log that fails verification. Free-form fields are scrubbed of card-like
numbers (Luhn-valid, 13-19 digits) before they are written. Known limit: truncating the tail
needs an externally anchored head to detect.

### 3. Cost and value are separate from the orchestrator (`Costs`, `Economics`)
`Payment` only *measures*: reductions, memory, duration, bytes in and out (a proxy: the
external term size of each call), state changes, provider calls, compensations, plus price
discovery (chosen net against the median, worst and best quote) and guarantee margin. It emits
one `tx_economics` event. `Economics` prices that with an explicit rate card and keeps totals,
overall and per provider. Costs are in **pico-units** (10^-12 of a major unit) because one
transaction costs a tiny fraction of a cent. Prices are placeholders to be replaced by invoice
figures.

"Saved" always has a stated baseline (the median quote). The price of non-price goals
(`regret vs best`) is shown, not hidden.

## Consequences

- Rounding is now explicit and testable; guarantees, ledger and selection are exact.
- Raw measurements live in the audit log, so any cost figure can be recomputed under a
  different rate card.
- Cost attribution covers the orchestrator process only; provider-side and VM-wide cost is
  covered by the platform line and the Metrics sampler, not per transaction.
- Costs and values are assumed to share a reporting currency.
- The audit writer is not yet supervised or fail-closed (see compliance gaps).

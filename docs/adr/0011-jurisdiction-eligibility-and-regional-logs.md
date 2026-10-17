# ADR 0011: Jurisdiction as a hard constraint, and regional logs

Status: accepted (2026-10-17)

## Context

The scope is spot and forward, business and some retail customers, across eleven
jurisdictions (UK, EU, US, India, Mexico, Brazil, Turkey, Saudi Arabia, Japan, South Korea,
China). Regulation decides which providers may carry a corridor, which instruments and
customer types are allowed, and where the records may live. Those are not preferences to
weigh in a score. See `docs/compliance/COMPLIANCE_EVIDENCE.md`.

## Decisions

### 1. Eligibility is applied before scoring (`ActorTxSpike.Eligibility`)
Declarative, validated rules per region plus a default. A transaction touches the regions
named by its `origin_region` and `destination_region`; the **strictest result wins**:
an instrument or customer type must be allowed by all, a provider by all, retention is the
longest, and records go to the union of the regions' logs. Ineligible providers are never
asked (they should not receive the request). A transaction whose instrument or customer type
is not permitted stops before any provider is called. The decision, including who was ruled
out and why, is recorded in `tx_received`, so replay can prove it (`eligibility_respected`).

### 2. Regional logs (`Audit.Router`)
One hash-chained log per named region (`<dir>/<name>.log`), each independently verifiable and
deliverable. A region rule may be `local_only`: a transaction touching it is recorded **only**
in the local-only regions' logs, not in the global one. Events without a transaction (health,
operator actions) go to `GLOBAL`. The router is one durable sink, so the write-ahead barrier
covers every region. A decision naming a log the router does not have **crashes the router**
(fail closed): the record has nowhere lawful to go. A regional log that dies takes the router
with it.

### 3. Residency is provable (`Replay.Residency`, `mix replay residency DIR`)
Given the logs, every transaction must appear in exactly the logs its own decision named: not
in one it must not be in (a leak), not missing from one it must be in.

## Limits (stated plainly)

- The rules are caller-supplied data and are real only once counsel confirms them per country;
  the demo's IN and CN rules are illustrative.
- Retention days are recorded in the decision but **not enforced**: no rotation, archive or
  deletion exists. Deleting a prefix of a chain needs an anchored head to keep it verifiable.
- A corridor that touches a local-only region leaves the other region without a record of that
  transaction; whether that satisfies the other region's rules is a legal question.
- Sanctions and KYC gates, forward deal lifecycle, trade reporting and the retail flows are not
  built; `instrument` is only an eligibility attribute today.
- Cut-off rules, policy cascade and guarantees are still per transaction, not per jurisdiction.

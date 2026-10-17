# Compliance evidence map

> **Not legal advice.** This maps what the system does today to what auditors and regulators
> typically ask for, so you can see the gaps. Take it to a QSA (PCI) and to compliance counsel
> (Dodd-Frank and UK/EU equivalents) before relying on any of it.

Status key: ✅ in place · 🟡 partial · ❌ gap · ➖ not applicable to this layer.

## 1. Scope decisions that shrink the problem

1. **The orchestrator never handles card numbers.** It moves provider tokens, quotes and
   references. Anything that stores, processes or transmits cardholder data is in PCI scope;
   keeping that out of this system is the biggest control there is. `Audit.scrub/1` redacts
   any Luhn-valid 13-19 digit run before it reaches disk as defence in depth, but it is not
   a substitute for never receiving the data.
2. **What regulation applies depends on the instrument** (section 3). Spot conversion,
   forwards and swaps, and consumer remittances are regulated differently. **This is an open
   question for you** (see section 5).

## 2. PCI DSS v4.0.1

What an auditor wants is *evidence that controls operate*: a Report on Compliance (QSA) or a
Self-Assessment Questionnaire plus an Attestation of Compliance. Evidence this system can
produce today is the **audit log** (`mix audit.verify`) and the **test and CI record**.

| Requirement family | What exists | Status | Evidence / gap |
|---|---|---|---|
| 1-2 Network security, secure configuration | Dashboard binds to loopback only; POST needs `application/json` | 🟡 | No TLS, no authentication (R4). Deployment hardening is outside this code |
| 3 Protect stored account data | No PAN handled by design; audit scrubbing; nothing persisted except the audit log | 🟡 | Requests are not *rejected* if they carry card-like data; add input validation |
| 4 Protect data in transit | Providers are fakes | ❌ | TLS to real providers is an adapter requirement |
| 5 Malware | n/a at this layer | ➖ | Platform |
| 6 Secure development | CI (format, Credo, Sobelow, mix_audit, 3 test tiers, mutation testing), ADRs, zero runtime dependencies | 🟡 | CI is not a required check (R18); no enforced code review; no SBOM |
| 7-8 Access control, authentication | None | ❌ | The control API can submit transactions and change provider behaviour |
| 9 Physical | n/a | ➖ | Platform |
| 10 Log and monitor | Hash-chained audit log of every event; ordered, time-stamped (system clock), tamper-evident, verifiable | 🟡 | See below |
| 11 Test security | Sobelow, mix_audit, fault-storm tests | 🟡 | No penetration test, no external vulnerability scan |
| 12 Policies, risk, incident response | ENGINEERING_STORY risk register, `needs_review` escalation | 🟡 | No formal policies or incident process |

### Requirement 10 in detail (the one this system can best evidence)

| Expectation | Where we stand |
|---|---|
| Audit trail links every action to a user or system component | ✅ every record carries the actor (`payment`, `provider:x`, `selector`, `economics`) |
| Events recorded: access, changes, failures, privileged actions | 🟡 transaction and provider events yes; **no user identity**, because there is no authentication yet |
| Date and time on every record | ✅ millisecond system time; ❌ clock synchronisation (NTP) is a platform duty |
| Logs protected against modification | 🟡 tamper-**evident**, not tamper-proof: edits, deletions and reorderings are detected at the first affected record. **Truncating the tail is not detectable by the chain alone**; anchor `head` externally |
| Logs backed up to a separate, secure system | ❌ single local file |
| Retention: 12 months, 3 months immediately available | ❌ no rotation or archival |
| Automated review, alerting on log failure | ❌ the log is not under the application supervision tree, so if the writer dies the system keeps running unlogged. A payments system should stop or alert (see roadmap) |

## 3. Dodd-Frank

Two different parts of the Act could apply. Which one depends on what you actually trade and
for whom.

| If the product is... | Relevant part | What it asks for | Where we stand |
|---|---|---|---|
| **Spot FX conversion** (settles in about two days) | Generally outside the Title VII swap rules | Standard financial recordkeeping | The audit log and quote records are a good base |
| **FX forwards or swaps** | Title VII: such contracts are exempt from the *swap* definition by Treasury determination, but still carry reporting to swap data repositories, business-conduct duties and recordkeeping | Complete trade records, timestamps, counterparty identifiers, reporting, about 5 years' retention with prompt retrieval | ❌ no counterparty identifiers (LEI/UTI), no reporting, no retention policy |
| **Consumer cross-border transfers** | Section 1073 (CFPB remittance rule, Regulation E subpart B) | Pre-payment disclosure of rate, fees and amount received; a receipt; a short cancellation window; error-resolution procedures; records kept about 2 years | 🟡 quote, rate, fee and net are captured per transaction. ❌ no disclosure, receipt, cancellation or error-resolution flow |

You mentioned a UK cut-off. If UK or EU entities or customers are involved, UK EMIR / EMIR,
the Payment Services Regulations and FCA record-keeping rules are likely relevant too
(typically 5 years' retention). That is for counsel to confirm.

### Why the audit log is the right foundation

Every regime above asks for the same thing underneath: *a complete, ordered, retrievable,
trustworthy record of what was quoted, agreed, paid and settled, and when*. Each transaction's
lifecycle is already in the log, including the quoted rate and fee, the alternatives that were
considered, every provider call, failures, compensations and the final outcome. What is missing
is mostly **retention, retrieval, identity and reporting**, not capture.

## 4. What an auditor can be given today

```
mix demo --audit audit/demo.log     # or start Audit under your own supervisor
mix audit.verify audit/demo.log     # OK: N records, head <sha256>
```

Each line is one JSON record: sequence `n`, event `seq`, timestamp, transaction id, actor,
kind, message, structured data, the previous record's hash and its own hash. Per transaction
the log also holds a `tx_economics` record (raw usage and price discovery) and a `tx_costed`
record, so the cost and savings figures are reproducible from the raw facts.

**Procedure to close the truncation gap:** at intervals, record the output of
`Audit.head/1` (`records`, `head`) somewhere independent of the host, such as a ticket or a
separate system. A log that later verifies but is shorter than an anchored head has lost its tail.

## 5. Gaps, in priority order

1. **Supervise the audit writer and fail closed** (stop or alert if it dies).
2. **Authentication and identity** on the control API, so records carry a user.
3. **Retention, rotation, off-host copy** and an anchoring job.
4. **Reject card-like data in requests**, not only scrub it from logs.
5. **Settle the instrument and jurisdiction questions**, then add what they demand: counterparty
   identifiers and reporting (forwards/swaps), or disclosure, receipt, cancellation and
   error-resolution flows (consumer remittance).
6. TLS, SBOM, enforced code review, penetration test.

### Questions only you can answer

- Which instrument: spot, forward or swap? Consumer or business customers?
- Which jurisdictions, and is there a UK or EU entity?
- Are you the merchant of record or a PCI **service provider** for others? That changes who
  attests to what.
- Do the real providers return a token only, or can card data ever reach you?

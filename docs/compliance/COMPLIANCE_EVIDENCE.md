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

### Replay as evidence

`mix replay verify` re-checks a whole day against the money-safety and decision rules from
the log alone and names the rule, transaction and audit record for any breach. It is a
repeatable, independent answer to "show me this day ran correctly", and `mix replay diff`
supports reconciling against another system. See ADR 0009 for its limits.

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

## 5. Your scope (answered 2026-10-17)

| Question | Answer |
|---|---|
| Instruments | **Spot and forward** |
| Customers | Mainly **business**, some **retail** |
| Jurisdictions, for now | **UK, EU, US, India, Mexico, Brazil, Turkey, Saudi Arabia, Japan, South Korea, China**; eventually every jurisdiction |

Consequences, before any jurisdiction detail:

1. **Forwards are a different kind of product from what the system does today.** Today's
   lifecycle (quote, accept, authorise, pay, settle) completes in seconds. A forward is agreed
   today and settles on a **value date days or months away**. It needs a long-lived, durable
   record of the deal, confirmation, maturity settlement, possibly collateral or margin, and
   **trade reporting** (counterparty and trade identifiers). `Payment` is a short-lived process
   with nothing persisted, so forwards need the durable store (roadmap Phase 3) first.
2. **Jurisdiction becomes a hard constraint, not a preference.** Which provider may be used for
   a corridor, whether a forward is allowed for this customer, and where the records may live
   are all rules that must be applied *before* scoring, exactly like `max_bps_from_best` and
   excluded health states today. The policy cascade is the natural home.
3. **Records have a home.** Several of these jurisdictions require payment or personal data to
   stay in-country or restrict transfer. One global audit log will not satisfy that: logs
   (and the journal and replay data in them) need a **region** and a retention period per region.
4. **Retail brings consumer-protection duties** (disclosure of rate, fees and amount received,
   receipts, cancellation, error resolution) that business flows mostly do not.

## 6. Jurisdiction matrix (orientation only)

> **Confidence and limits.** Written from general knowledge on 2026-10-17, **not checked
> against primary sources**, and regulation in several of these places has changed recently.
> Use it to know *which questions to ask counsel in each country*, not as the answer. Specific
> thresholds, fees and dates are deliberately left out.

| Jurisdiction | Who typically regulates / licenses | Spot and forward, in outline | Records and data | Headline for this system |
|---|---|---|---|---|
| **UK** | FCA; payments under the Payment Services Regulations 2017 (authorised payment or e-money institution) | Spot is generally outside derivatives rules; forwards are in scope of UK EMIR (reporting to a trade repository) and FCA rules unless they are commercial means-of-payment forwards. Retail adds consumer-protection duties | About 5 years typical for financial records; UK GDPR | Authorisation as the payment entity; EMIR reporting for forwards; safeguarding of customer funds |
| **EU** | National regulators under PSD2 (PSD3/PSR coming), EMIR, MiFID II, **DORA** | Forwards: EMIR reporting; MiFID II treatment depends on purpose. Spot generally outside | GDPR; **DORA** adds ICT risk, incident reporting and a register of third-party ICT providers | Our providers are third-party ICT services: DORA-style oversight, exit plans and incident handling apply to a payment institution |
| **US** | Federal (FinCEN, OFAC, CFTC, CFPB) and **state money-transmitter licences** | Spot and forwards: FX forwards and swaps are excluded from the "swap" definition but stay subject to reporting and anti-fraud rules (Dodd-Frank Title VII, see section 3). Retail cross-border: CFPB remittance rule | BSA/AML record-keeping (about 5 years typical); OFAC sanctions screening | State-by-state licensing is the long pole; remittance disclosures for retail |
| **India** | RBI (and FEMA); authorised dealers; RBI-authorised cross-border payment aggregators | Forwards for residents generally need an underlying exposure; retail has the Liberalised Remittance Scheme | RBI requires payment data to be stored **in India** | Data localisation; who the licensed counterparty is; underlying-exposure checks on forwards |
| **Mexico** | Banxico, CNBV, the Fintech Law (2018) | Licensing for payment and FX activity; AML rules apply | Local AML record-keeping | Local licence or a licensed partner |
| **Brazil** | Central Bank of Brazil (BCB) under the 2021 FX framework | FX operations go through authorised institutions and are **reported to the BCB**; a transaction tax on FX applies | LGPD | Mandatory FX reporting per operation; tax calculation; authorised-institution routing |
| **Turkey** | CBRT and BDDK; payment services law | Tight FX controls; foreign firms generally need a local licence or partner | Payment-system information systems are expected to be **in Turkey** | Local presence or partner; data residency |
| **Saudi Arabia** | SAMA (payments law and licensing); PDPL | Licensing for payment services and FX; outsourcing and cloud rules from SAMA | SAMA cyber and outsourcing frameworks; PDPL | Licensed entity; cloud and third-party controls |
| **Japan** | FSA / JFSA: Payment Services Act (funds transfer), FIEA (derivatives), FEFTA (reporting) | Forwards are derivatives under the FIEA (registration); cross-border reporting under FEFTA | APPI | Registration categories; FEFTA reporting |
| **South Korea** | MOEF / FSS; Foreign Exchange Transactions Act; Electronic Financial Transactions Act | Registration for overseas remittance; forwards for residents tied to real underlying demand; per-person limits exist | PIPA | Registration and limits |
| **China** | SAFE (FX controls), PBoC (payment licences) | Strict capital controls; forwards need real underlying demand; foreign firms generally operate through licensed domestic institutions | PIPL, Data Security Law and Cybersecurity Law: **data localisation and cross-border transfer review** | Domestic licensed partner; data must stay; transfer reviews |

### What cuts across all eleven

- **Sanctions and AML/KYC screening** of parties and payments (OFAC, UK, EU lists and local
  equivalents) before money moves. This sits ahead of `accept`, it is a hard stop, and every
  screening decision belongs in the journal.
- **Licence holder:** which legal entity is the regulated party in each country, and whether
  the provider (not us) holds the licence for that corridor.
- **Data residency and transfer:** India, China, Turkey and Saudi Arabia are the strictest
  here; the EU and others restrict transfers outside adequacy rules.
- **Trade and FX reporting:** forwards (EMIR, US trade reporting) and several countries'
  per-operation FX reporting (Brazil, Japan, others).
- **Retention:** differs by country; the strictest applicable rule should set the minimum.

## 7. What this means for the design (proposed)

| Change | Why | Where it fits |
|---|---|---|
| **Jurisdiction eligibility as a hard constraint** in routing: per corridor, which providers, instruments and customer types are allowed | Provider choice is regulated, not just priced | New constraint ahead of scoring in `Policy`/`PolicySet`; recorded in the journal so `Replay.Rules` can prove it |
| **Compliance gates before `accept`**: sanctions and KYC screening as explicit steps with recorded outcomes | Money must not move for a screened party | A new lifecycle step; a provider-like adapter for the screening service |
| **Per-region audit logs and retention** | Data residency and differing retention | `Audit` instance per region; routing of events by region; retention policy per region |
| **Forward deals as a long-lived record** with value date, confirmation, maturity settlement and trade identifiers | Forwards are not seconds long | Durable store (Phase 3); a deal lifecycle distinct from `Payment` |
| **Trade and FX reporting** as an outbound, audited flow | EMIR, US, Brazil and Japan | After the durable store; reports reproducible from the log |
| **Retail flows**: disclosure, receipt, cancellation window, error resolution | Consumer protection | A retail variant of the lifecycle, built on the same journal |

## 8. Gaps, in priority order

**Built since this list was written (2026-10-17):** fail closed when the log is unavailable
(write-ahead barrier), one writer per log, recovery of open transactions from the log
(ADR 0010); jurisdiction eligibility, regional logs and provable residency (ADR 0011).
Still open from the items below: supervising the audit writer in the application tree and
refusing to start without it; retention, rotation and off-host copy; sanctions and KYC gates;
the forward deal lifecycle and trade reporting; counsel's confirmation of every rule.

1. **Supervise the audit writer and fail closed** (stop or alert if it dies). *Fail closed built; supervision and refuse-to-start still open.*
2. **Authentication and identity** on the control API, so records carry a user.
3. **Retention, rotation, off-host copy** and an anchoring job.
4. **Reject card-like data in requests**, not only scrub it from logs.
5. **Durable store**, then the **forward deal lifecycle** and **trade reporting**.
6. **Jurisdiction eligibility** in the policy, and **sanctions/KYC gates**.
7. **Per-region logs and retention** for data residency.
8. TLS, SBOM, enforced code review, penetration test.

### Still only you can answer

- Which entity is the regulated party in each of the eleven countries, and where do you hold
  licences today (or rely on a licensed provider)?
- For forwards: do you take the deal on your own book, or arrange it with a provider? That
  decides who reports and who holds collateral risk.
- Are you the merchant of record or a PCI **service provider** for others?
- Can card data ever reach you, or only provider tokens?
- Which three or four jurisdictions come first? Counsel's time is best spent there.

Next step on the regulatory side: have counsel in each priority jurisdiction check the matrix
above, then turn the confirmed rules into policy data and journal rules so replay can prove them.

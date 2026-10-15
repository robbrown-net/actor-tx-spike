# ADR 0002: Commit-step safety rules

Status: accepted · Date: 2026-10-15 · Supersedes the "quote freely, commit once" sketch in ADR 0001 Amendment 1

## Context
The real lifecycle has several effectful provider steps (accept, authorise, pay) and
providers can answer slowly, wrongly or not at all. Naive failover on timeout risks
paying twice; naive "fail" on timeout risks leaving money held.

## Decision
1. Quoting is free and hedged. **Every later step is effectful** and carries its own
   idempotency key `"<tx>:<provider>:<step>"`, reused on retry.
2. **Definite failures** (technical error): bounded retry with the same key. Then
   compensate what was done at that provider and fail over to the next-ranked quote.
3. **Doubt** (timeout, answer lost): ask the provider (`status(op, key)`) before doing anything else.
   * Accept / authorise (reversible): if still unconfirmed, **cancel by key** (idempotent, defeats a late effect) and fail over.
   * Pay: **never fail over while in doubt.** Reconcile a bounded number of times, then `needs_review`.
4. Compensation is a first-class provider operation (`cancel_accept`, `void_authorisation`, `refund`), keyed by the effect's key, idempotent, and safe for a key that never took effect.
5. Compensation that cannot complete is escalated (`needs_review`), never ignored.

## Alternatives considered
* *Fail over on any timeout*: simplest, unsafe for money.
* *Retry pay on a different provider with a new key*: double-payment risk.
* *Two-phase commit across providers*: providers do not support it.

## Consequences
* A small, explicit set of states a human may have to resolve.
* Providers must support idempotent keys and undo-by-key (assumed; to be validated against real APIs).
* `status` is allowed to be stale, so "not found" means "not known yet".
* The randomised storm test asserts these rules as invariants.

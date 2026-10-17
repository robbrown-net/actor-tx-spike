# ADR 0009: Replay the day from a journal in the audit log

Status: accepted (2026-10-17)

## Context

The business needs to replay a day to (1) recreate the exact conditions at a moment,
(2) verify in hindsight that the day followed every rule, (3) compare with another system
running in parallel, and (4) test different rule strategies against a real day and choose
what to tweak. The narrative log told a human what happened but did not hold enough to
re-judge a decision: quotes had no expiry, policies were named not recorded, provider
answers and latencies were not kept, operator actions were not logged.

## Decisions

### 1. One log, two audiences: add a journal to the existing stream
Rather than a second log, the existing hash-chained audit log gains **journal** events
(`ActorTxSpike.Journal`): every *input* from the outside world and the context needed to
re-judge every *decision*. Journal events carry the actor `:journal`; the dashboard and
terminal hide them, the audit log keeps them. A replayable day is therefore also a tamper-
evident day, and one verification covers both. Schema v1 is in the `Journal` moduledoc.

| Input / context | Recorded as |
|---|---|
| The request, the resolved policy, guarantee, settings, providers | `tx_received` (enriched) |
| What each provider answered to a quote request, with health state, latency and time seen | `quote_observed` |
| What each provider answered to every later call, with key, attempt, latency | `provider_response` |
| Operator and world actions (faults, repricing, pace) | `control` |
| Provider health over time | `health` (existing) |

### 2. Decisions are replayed with the live code, not a copy
The selection rule was extracted into a pure function, `Selector.evaluate/3`. The live
system calls it with what the providers just said; replay calls it with what the log says
they said. Same code, so a mismatch is a real finding, not a modelling difference. The
recorded policy is rebuilt exactly (weights are not re-normalised) so scores reproduce
to the last bit.

### 3. Four replay uses, one loader (`ActorTxSpike.Replay`)
Loading verifies the hash chain and refuses a log that fails. Records are the decoded JSON
(string keys), so any system writing this format can be loaded.

| Use | Module | Notes |
|---|---|---|
| State at a moment | `Replay.state_at/2` | By audit record `n`, event `seq` or time. In-flight and finished transactions, provider health and last price, policy in force, operator actions, economics so far |
| Verify the day | `Replay.Rules` | Money safety (single pay, completed is whole, failed is clean, no failover after doubt, idempotency keys), decisions (selection reproducible, quote valid at accept, deadline respected, margin arithmetic, economics consistent), hygiene (time ordered). Violations name the rule, transaction and audit record |
| Compare with another system | `Replay.Compare` | Matched by transaction id on outcome, provider, net and steps. Timing is not compared |
| Try another strategy | `Replay.WhatIf` | Re-judge recorded answers under a changed policy; reports changed routes and the net and latency effect |

CLI: `mix replay verify | txs | at | whatif | diff`.

## What this does not do (stated plainly)

- **What-if is quote-level.** It sees only providers that were asked and assumes the
  alternative would have completed. It cannot know how a provider the policy would have
  preferred would really have behaved. It measures the policy change alone.
- **It does not re-execute the orchestrator.** `state_at` describes the conditions; it does
  not rebuild a running world from them. Re-running `Payment` against recorded answers needs a
  virtual clock and a replay provider adapter (roadmap).
- **Cascade source data is not recorded**, only the resolved policy. Replaying a change to
  the merchant or customer layer itself needs the layers captured.
- **Cost what-if** needs the rate card captured in the log; today it is not.
- **Time-dependent behaviour** (backoff timers, scheduling) is not recorded; only outcomes are.
- **Volume:** about 60 records and 23 KB per transaction in the sample day, plus background
  health records. At 1M transactions a day that is roughly 23 GB uncompressed; JSON compresses
  well, but retention and tiering (a roadmap item) become necessary.

## Consequences

- Every new decision rule must either be re-derivable from journal data or add the inputs it
  needs; adding a rule without its journal inputs makes the day unreplayable. Test: the replay
  tests break a good day one way at a time and expect exactly that violation.
- The audit log is now the source of truth for operations, evidence and replay at once, which
  raises the cost of the open gap that it is not supervised or fail-closed.

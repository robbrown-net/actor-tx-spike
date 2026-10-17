# ADR 0010: Write-ahead log, fail closed, and crash recovery

Status: accepted (2026-10-17)

## Context

Everything lived in memory: a restart lost every in-flight transaction, and money could
be half-moved with no record of it. The audit log was written after the fact, by a writer
that could die unnoticed. Forwards and regulated rollout both need transactions to survive
a restart, and the evidence to be written before the effect, not after.

## Decisions

### 1. The log is written ahead of every money-moving call
`Events.register_sink/3` lets a durable sink (the audit log, or the regional router)
register. `Events.barrier/1` returns only once every sink has written everything emitted
before the call. `Payment` calls it before each accept, authorise, pay, settle and each
undo. So after a crash the log is never missing a call that was actually sent:

- a call **in the log** may have reached the provider (the answer may be lost): it is
  reconciled by `status`, never repeated blindly;
- a call **not in the log** was never sent: it is safe to make.

Status checks and quote requests are not write-ahead (they move no money). The barrier is
called by the transaction, not the event server, so a slow sink cannot wedge the stream.

### 2. Fail closed
If a registered sink has died, `barrier` fails until a replacement registers under the same
name; if it is merely unresponsive, that call fails. `Payment` then moves no money: a
transaction that has done nothing yet fails (`audit_unavailable`); one that has moved money
goes to `needs_review`. A sink that stops *on purpose* unregisters first; a crash does not.

### 3. One writer per log, group commit
- A chain has one head, so each log takes `<log>.lock` naming its OS and Erlang process; a
  second writer is refused, and a lock whose owner is gone is taken over. (A real overlap of
  two instances on one log was seen during testing and broke the chain.)
- Records are written at once and made durable together: one fsync on each barrier and at the
  latest every 50 ms, instead of one per record, which capped throughput at the disk's fsync
  rate and starved the barrier under load.

### 4. Recovery from the log (`ActorTxSpike.Recovery`)
`Recovery.plan/1` is a pure function from one transaction's records to where it stood.

| mode | meaning | resumes by |
|---|---|---|
| `fresh` | no provider chosen yet | quoting again (quoting has no side effects) |
| `next` | the last step succeeded, or a retry was pending | sending the next step, which was never sent |
| `doubt` | a step was sent with no answer recorded | asking the provider what it holds |
| `undo` | compensation was under way | finishing the remaining undos (after a deadline abort, aborting again) |
| `complete` | every step done, completion not recorded | recording it |
| `review` | the log cannot say | `needs_review` |

`Recovery.recover/2` starts a resumed `Payment` per open transaction, once even if it appears
in several regional logs. The money rules then apply unchanged: an unresolved pay or settle is
never failed over; an unconfirmed reversible step is cancelled by key. A transaction whose
provider no longer exists, or whose quote was never recorded, is escalated, not guessed.

### Evidence it works
Pure plan tests for every position; resume against a real run cut off at nine points
(nothing sent, and in-doubt and done for each of accept, authorise, pay, settle) plus before
choosing, mid-undo, after the last undo, and after a missed cut-off; random kills of a running
transaction at ten moments asserting at most one payment whatever the timing; a restart from
regional logs; and a demo that resumes a transaction killed mid-flight.

## Limits (stated plainly)

- **Fail closed holds only while a sink is registered.** A system started with no audit log
  runs unlogged, as development and most tests do. Production must register one (and, still
  to do, supervise it in the application tree and refuse to start without it).
- A pay that was *never sent* but is in the log as started is reconciled as in doubt, and an
  unprovable "not found" for pay goes to a human. Conservative by design, so a crash in that
  window costs a human review rather than risking a double payment.
- A resumed transaction's measured cost covers only its second life; its policy label is not
  restored; a resumed quote may have expired, which then fails over normally.
- The simulated demo world forgets its provider state on restart (it is a simulation of the
  outside world); real providers would remember.
- Recovery needs the provider registry at restart to contain the same provider ids.
- Nothing is rotated, archived or deleted yet; retention is recorded, not enforced.

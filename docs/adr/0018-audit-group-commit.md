# ADR 0018: Audit group commit

Status: accepted (2026-10-24)

## Context

The Java server, with the audit log on, topped out at about 10 payments per second at every in-flight cap, against 200 to 300 without it. The cause was in the barrier. Each barrier ran `FileChannel.force` on the event-bus dispatcher thread, so every payment step waited behind the disk, one force at a time. Measured with the same workload: about 4.1 forces per payment, each covering about 15.6 records, about 42 forces per second. See [Performance Analysis/java-group-commit.md](../../Performance%20Analysis/java-group-commit.md).

The barrier's guarantee is the reason it exists: a payment's money step must not proceed until the records announced before it are on disk, and a failed force must stop the step. That guarantee is not up for change.

## Decision

1. **The barrier is group commit.** Records are appended to the file as the dispatcher delivers them, in order, and not forced there. A barrier records how far its log has been written and waits for a force that started after that point and returned successfully. Barriers that arrive while a force runs share the next force.
2. **One sync thread per audit log performs every force.** The dispatcher starts the flush of each sink and goes on to the events behind it. The disk is never waited on from the dispatcher.
3. **Nothing acknowledges before it is durable, and no timer lets a barrier return early.** A timer exists only for records nobody asks about (forced within 50 ms of being written). It does not complete a barrier.
4. **A failed force fails every barrier waiting on it**, with the same reason as before (`audit_unavailable` at the payment). The log refuses writes from then on, so the failure is sticky.
5. **Unchanged:** hash-chain order, the one-writer lock file, regional routing, the scrubbing of card numbers before the disk, and the fail-closed rule for a sink that dies.

The bus's sink interface gains a non-blocking `flushAsync`. The synchronous `flush` stays, and a sink that does not batch gets the old behaviour by default.

## Consequences

- Forces per payment fell from about 4.1 to 2.1 to 2.5, and records per force rose from about 15.6 to 26 to 31. Throughput rose from about 10 to 17 to 20 payments per second (16.7, 18.4 and 20.2 at in-flight caps 20, 80 and 200).
- The gain is capped by the force rate. The disk completed about 42 forces per second in both runs, so throughput is that rate divided by forces per payment. Further gains need fewer barriers per payment, which is a design question for a later ADR.
- The guarantee is tested directly: a barrier stays pending while its force is blocked and the file already holds the records; a failed force fails all waiters; concurrent appenders share forces and the chain still verifies.
- Each open audit log now has a daemon thread. Interrupting it is absorbed while it waits. Interrupting it during a force would close the file channel (Java closes interruptible channels), so nothing interrupts it outside tests, and a channel closed that way fails closed.
- `/api/metrics` reports `audit` (records, syncs, synced records, records per sync). The router logs the same on close; that shutdown line has not been seen on a `kill` stop.

## Open

- Which system call the JDK issues for `FileChannel.force` on macOS (a plain `fsync`, or `F_FULLFSYNC`, which also flushes the drive's cache) is not verified. The semantics stay "forced to the disk" until it is recorded here.
- Fewer barriers per payment is the next lever, and needs its own decision.

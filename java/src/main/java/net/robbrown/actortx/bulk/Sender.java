package net.robbrown.actortx.bulk;

import net.robbrown.actortx.service.PaymentCommand;

/**
 * The sending loop: one command at a time, a pause between submissions, a retry of any command the
 * system refused as busy, and a wait while the plan's own in-flight cap is reached. It stops when
 * the plan's count has been submitted, when the tally is no longer running, or when interrupted.
 */
final class Sender {

  /** How often a sender at its own cap looks again. */
  private static final long POLL_MS = 5;

  private final Plan plan;
  private final CommandGenerator generator;
  private final Submitter submitter;
  private final Tally tally;
  private final Sleeper sleeper;
  private PaymentCommand held;

  Sender(Plan plan, CommandGenerator generator, Submitter submitter, Tally tally, Sleeper sleeper) {
    this.plan = plan;
    this.generator = generator;
    this.submitter = submitter;
    this.tally = tally;
    this.sleeper = sleeper;
  }

  /** Send until done or stopped. Returns; an interrupt ends it quietly (the flag is restored). */
  void run() {
    try {
      while (tally.running() && !tally.sendingDone()) {
        step();
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private void step() throws InterruptedException {
    if (tally.atCap()) {
      tally.waited();
      sleeper.sleep(POLL_MS);
    } else {
      send();
    }
  }

  private void send() throws InterruptedException {
    PaymentCommand command = nextCommand();
    switch (submitter.submit(command)) {
      case Submission.Accepted accepted -> {
        tally.accepted(accepted, command.amount());
        pause(plan.intervalMs());
      }
      case Submission.Invalid invalid -> {
        tally.invalid();
        pause(plan.intervalMs());
      }
      case Submission.Busy busy -> {
        held = command;
        tally.busy();
        pause(Math.max(busy.retryAfterMs(), plan.intervalMs()));
      }
    }
  }

  /** A command refused as busy is sent again rather than skipped. */
  private PaymentCommand nextCommand() {
    if (held == null) {
      return generator.next();
    }
    PaymentCommand command = held;
    held = null;
    return command;
  }

  /** No pause after the last submission. */
  private void pause(long millis) throws InterruptedException {
    if (!tally.sendingDone()) {
      sleeper.sleep(millis);
    }
  }
}

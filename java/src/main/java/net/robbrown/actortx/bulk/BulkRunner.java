package net.robbrown.actortx.bulk;

import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.random.RandomGenerator;
import net.robbrown.actortx.events.EventSink;
import net.robbrown.actortx.service.InvalidInputException;
import net.robbrown.actortx.validation.Validated;

/**
 * Sends a batch of payments in the background, one at a time, and keeps score of how they end.
 *
 * <p>Only one batch runs at a time. A batch is a {@link Plan}; it runs on a virtual thread, so
 * {@link #start} returns at once and {@link #snapshot} shows progress while it runs. {@link #stop}
 * stops sending (what was already sent still ends). The batch's start and end go to the narrative
 * ({@link EventSink}).
 *
 * <p>Clock, sleeper and random source are injected, so tests run without waiting. A seeded plan
 * draws from its own seed; an unseeded one draws from the injected random source.
 */
public final class BulkRunner {

  private final Submitter submitter;
  private final Clock clock;
  private final Sleeper sleeper;
  private final RandomGenerator random;
  private final EventSink events;
  private Batch batch;

  /** The latest batch: its tally and the thread sending it. */
  private record Batch(Tally tally, Thread thread) {}

  /**
   * A runner with no batch in progress.
   *
   * @param submitter where each payment is sent
   * @param clock the time source for elapsed time and latency
   * @param sleeper pauses the sender between submissions
   * @param random the random source for unseeded plans
   * @param events where the batch's start and end are announced
   */
  public BulkRunner(
      Submitter submitter, Clock clock, Sleeper sleeper, RandomGenerator random, EventSink events) {
    this.submitter = submitter;
    this.clock = clock;
    this.sleeper = sleeper;
    this.random = random;
    this.events = events;
  }

  /**
   * Start a batch from its parameters (as decoded from JSON).
   *
   * @param params the plan's parameters
   * @return the plan in words
   * @throws InvalidInputException when the plan is not valid; carries every problem
   * @throws IllegalStateException when a batch is already running
   */
  public synchronized String start(Map<String, Object> params) {
    if (running()) {
      throw new IllegalStateException("a batch is already running: stop it first");
    }
    Plan plan = validated(params);
    Tally tally = new Tally(clock, plan);
    Sender sender =
        new Sender(
            plan, new CommandGenerator(plan, plan.generatorFor(random)), submitter, tally, sleeper);
    events.emit(
        null,
        "operator",
        "bulk_started",
        "operator: bulk batch started: " + plan.describe(),
        Map.<String, Object>of("plan", plan.toMap()));
    batch = new Batch(tally, Thread.ofVirtual().start(() -> drive(tally, sender)));
    return plan.describe();
  }

  /**
   * Stop sending. What was already sent still ends and is still counted.
   *
   * @return {@code false} when no batch was running
   */
  public synchronized boolean stop() {
    if (batch == null || !batch.tally().stop()) {
      return false;
    }
    batch.thread().interrupt();
    announceEnd(batch.tally().snapshot());
    return true;
  }

  /**
   * Forget a finished or stopped batch, so the dashboard shows idle again.
   *
   * @throws IllegalStateException when a batch is still running
   */
  public synchronized void clear() {
    if (running()) {
      throw new IllegalStateException("stop the batch first");
    }
    batch = null;
  }

  /** Progress of the latest batch, or idle when there has been none. */
  public synchronized Progress snapshot() {
    return batch == null ? Progress.idle() : batch.tally().snapshot();
  }

  private boolean running() {
    return batch != null && batch.tally().running();
  }

  private static Plan validated(Map<String, Object> params) {
    return switch (Plan.of(params)) {
      case Validated.Valid<Plan> valid -> valid.value();
      case Validated.Invalid<Plan> invalid -> throw new InvalidInputException(invalid.errors());
    };
  }

  /** The batch's thread: send, wait for every payment to end, then announce the finish. */
  private void drive(Tally tally, Sender sender) {
    sender.run();
    tally.awaitEndings();
    if (tally.finish()) {
      announceEnd(tally.snapshot());
    }
  }

  private void announceEnd(Progress progress) {
    String status = progress.status().name().toLowerCase(Locale.ROOT);
    events.emit(
        null,
        "operator",
        "bulk_finished",
        "operator: bulk batch " + status + ": " + progress.submitted() + " sent",
        Map.<String, Object>of(
            "status",
            status,
            "submitted",
            progress.submitted(),
            "completed",
            progress.completed(),
            "failed",
            progress.failed(),
            "needs_review",
            progress.needsReview(),
            "throttled",
            progress.throttled(),
            "elapsed_ms",
            progress.elapsedMs()));
  }
}

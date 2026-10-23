package net.robbrown.actortx.bulk;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.robbrown.actortx.payment.Outcome;

/**
 * The counts of one batch (or one load run): what was sent, refused, retried and how each accepted
 * payment ended. Written by the sender and by the futures the submitter returns, so every method is
 * synchronised.
 */
final class Tally {

  private static final int RECENT = 12;
  private static final int REASON_CHARS = 110;
  private static final int REASON_KINDS = 40;
  private static final int TOP_REASONS = 8;
  private static final Comparator<Map.Entry<String, Integer>> MOST_COMMON =
      Map.Entry.<String, Integer>comparingByValue()
          .reversed()
          .thenComparing(Map.Entry.<String, Integer>comparingByKey());

  private final Clock clock;
  private final Plan plan;
  private final long startedMs;
  private final Map<String, Long> inFlight = new HashMap<>();
  private final List<CompletableFuture<Ending>> endings = new ArrayList<>();
  private final List<Long> latencies = new ArrayList<>();
  private final Map<String, Integer> reasons = new HashMap<>();
  private final Deque<Progress.Sent> recent = new ArrayDeque<>();
  private Progress.Status status = Progress.Status.RUNNING;
  private Long endedMs;
  private int submitted;
  private int errors;
  private int throttled;
  private int waited;
  private int completed;
  private int failed;
  private int needsReview;

  Tally(Clock clock, Plan plan) {
    this.clock = clock;
    this.plan = plan;
    this.startedMs = clock.millis();
  }

  synchronized boolean running() {
    return status == Progress.Status.RUNNING;
  }

  synchronized boolean sendingDone() {
    return submitted >= plan.count();
  }

  /** Is the sender at its own in-flight cap? Never, when the plan sets no cap. */
  synchronized boolean atCap() {
    return plan.maxInFlight() > 0 && inFlight.size() >= plan.maxInFlight();
  }

  synchronized void waited() {
    waited++;
  }

  synchronized void invalid() {
    submitted++;
    errors++;
  }

  synchronized void busy() {
    throttled++;
  }

  /** Count an accepted payment and watch for its ending. */
  void accepted(Submission.Accepted accepted, BigDecimal amount) {
    record(accepted.id(), amount);
    // the future returned by whenComplete finishes only after the ending is recorded
    CompletableFuture<Ending> recorded =
        accepted.ending().whenComplete((ending, error) -> ended(accepted.id(), ending, error));
    track(recorded);
  }

  /** Wait until every accepted payment has ended. */
  void awaitEndings() {
    CompletableFuture.allOf(snapshotEndings()).handle((done, failure) -> null).join();
  }

  /** Mark the batch as stopped; true when it was running and is now. */
  synchronized boolean stop() {
    return end(Progress.Status.STOPPED);
  }

  /** Mark the batch as finished; true when it was running and is now. */
  synchronized boolean finish() {
    return end(Progress.Status.FINISHED);
  }

  synchronized Progress snapshot() {
    long end = endedMs == null ? clock.millis() : endedMs;
    long elapsed = end - startedMs;
    return new Progress(
        label(),
        plan.describe(),
        plan.count(),
        submitted,
        errors,
        throttled,
        waited,
        inFlight.size(),
        completed,
        failed,
        needsReview,
        elapsed,
        Progress.round1(submitted * 1000.0 / Math.max(elapsed, 1)),
        topReasons(),
        List.copyOf(recent),
        Percentiles.of(latencies));
  }

  private synchronized void record(String id, BigDecimal amount) {
    submitted++;
    inFlight.put(id, clock.millis());
    recent.addFirst(new Progress.Sent(id, amount));
    if (recent.size() > RECENT) {
      recent.removeLast();
    }
  }

  private synchronized void track(CompletableFuture<Ending> recorded) {
    endings.add(recorded);
  }

  private synchronized void ended(String id, Ending ending, Throwable error) {
    long startedAt = inFlight.remove(id);
    latencies.add(clock.millis() - startedAt);
    count(error == null ? ending : new Ending(Outcome.FAILED, String.valueOf(error.getMessage())));
  }

  private void count(Ending outcome) {
    switch (outcome.status()) {
      case COMPLETED -> completed++;
      case FAILED -> {
        failed++;
        countReason(outcome.reason());
      }
      case NEEDS_REVIEW -> {
        needsReview++;
        countReason(outcome.reason());
      }
    }
  }

  /**
   * Group a reason by its shape: numbers taken out, long text cut, and a new kind beyond the cap is
   * "other".
   */
  private void countReason(String message) {
    String text = Objects.toString(message, "").replaceAll("[0-9][0-9.]*", "N");
    String key = text.substring(0, Math.min(text.length(), REASON_CHARS));
    boolean known = reasons.containsKey(key) || reasons.size() < REASON_KINDS;
    reasons.merge(known ? key : "other", 1, Integer::sum);
  }

  private synchronized CompletableFuture<?>[] snapshotEndings() {
    return endings.toArray(new CompletableFuture<?>[0]);
  }

  private boolean end(Progress.Status to) {
    if (status != Progress.Status.RUNNING) {
      return false;
    }
    status = to;
    endedMs = clock.millis();
    return true;
  }

  private Progress.Status label() {
    return status == Progress.Status.RUNNING && sendingDone() ? Progress.Status.DRAINING : status;
  }

  private List<Progress.Reason> topReasons() {
    return reasons.entrySet().stream()
        .sorted(MOST_COMMON)
        .limit(TOP_REASONS)
        .map(entry -> new Progress.Reason(entry.getKey(), entry.getValue()))
        .toList();
  }
}

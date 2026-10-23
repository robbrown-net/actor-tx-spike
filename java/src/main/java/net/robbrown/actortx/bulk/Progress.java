package net.robbrown.actortx.bulk;

import java.math.BigDecimal;
import java.util.List;

/**
 * A batch's progress as the dashboard (or the load tool) sees it. Counts are of what has happened:
 * {@code submitted} is what was sent, and each of those ends as completed, failed or needing review
 * (the rest are still in flight).
 *
 * @param status where the batch is
 * @param plan the plan in words, or {@code null} when idle
 * @param count how many were asked for
 * @param submitted how many were sent (accepted or refused as invalid)
 * @param errors how many were refused as invalid
 * @param throttled how many times the system was busy and the sender backed off to retry
 * @param waited how many times the sender waited for its own in-flight cap
 * @param inFlight how many accepted payments have not ended yet
 * @param completed how many completed
 * @param failed how many failed
 * @param needsReview how many need a person to look at them
 * @param elapsedMs from the start to the end (or to now while running)
 * @param perSecond submitted per second, one decimal place
 * @param reasons the most common reasons payments did not complete, with numbers taken out
 * @param recent the latest accepted payments, newest first
 * @param latency how long accepted payments took to end, in milliseconds
 */
public record Progress(
    Status status,
    String plan,
    int count,
    int submitted,
    int errors,
    int throttled,
    int waited,
    int inFlight,
    int completed,
    int failed,
    int needsReview,
    long elapsedMs,
    double perSecond,
    List<Reason> reasons,
    List<Sent> recent,
    Percentiles latency) {

  /** Where a batch is. */
  public enum Status {
    /** No batch has been started. */
    IDLE,
    /** Still sending. */
    RUNNING,
    /** Everything is sent; some payments have not ended yet. */
    DRAINING,
    /** Everything was sent and everything ended. */
    FINISHED,
    /** Stopped before everything was sent; what was sent has ended. */
    STOPPED
  }

  /**
   * A reason payments did not complete.
   *
   * @param text the reason, with numbers replaced by {@code N}
   * @param count how many payments had it
   */
  public record Reason(String text, int count) {}

  /**
   * An accepted payment.
   *
   * @param id its id
   * @param amount its amount in major units
   */
  public record Sent(String id, BigDecimal amount) {}

  /** What the dashboard shows when no batch has been started. */
  public static Progress idle() {
    return new Progress(
        Status.IDLE,
        null,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0.0,
        List.of(),
        List.of(),
        Percentiles.of(List.of()));
  }

  /** Rounds to one decimal place, as the dashboard shows rates. */
  static double round1(double value) {
    return Math.round(value * 10) / 10.0;
  }
}

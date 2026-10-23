package net.robbrown.actortx.review;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;
import java.util.Map;

/**
 * One transaction in the review queue: open until a person decides, with what is known about it and
 * the trail of what people did. Immutable; the queue replaces it when something changes.
 *
 * @param tx the transaction
 * @param status open, or resolved
 * @param openedAtMs when it entered review
 * @param reason why it needs review: the sentence the system wrote when it stopped
 * @param provider the provider the transaction was using, or {@code null} when none was chosen or
 *     the transaction is no longer known
 * @param done the steps that succeeded at that provider, in order
 * @param request the transaction's request as recorded; empty when it is not known
 * @param story what happened to the transaction, oldest first (the last 30 lines)
 * @param actions what people did, oldest first
 * @param resolution the decision, or {@code null} while open
 */
public record ReviewEntry(
    String tx,
    Status status,
    long openedAtMs,
    String reason,
    String provider,
    List<String> done,
    Map<String, Object> request,
    List<String> story,
    List<ReviewAction> actions,
    Resolution resolution) {

  /** Whether the review is still waiting for a decision. */
  public enum Status {
    /** Waiting for a person. */
    OPEN,
    /** Closed with a decision. */
    RESOLVED;

    /** The lower-case name the dashboard reads. */
    @JsonValue
    public String wire() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  /**
   * Something a person did: a check, a retry (requested, then its result), or a result.
   *
   * @param atMs when it happened
   * @param kind {@code "check"}, {@code "retry"} or {@code "result"}
   * @param by who did it
   * @param op the operation it concerned, or {@code null} for a check
   * @param detail what was asked or answered
   */
  public record ReviewAction(long atMs, String kind, String by, String op, String detail) {}

  /**
   * The decision that closed a review.
   *
   * @param outcome {@code paid}, {@code not_paid} or {@code written_off}
   * @param by who decided
   * @param note why
   * @param atMs when it was decided
   */
  public record Resolution(String outcome, String by, String note, long atMs) {}

  /** Is it still waiting for a decision? */
  public boolean open() {
    return status == Status.OPEN;
  }
}

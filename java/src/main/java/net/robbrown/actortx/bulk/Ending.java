package net.robbrown.actortx.bulk;

/**
 * How an accepted payment ended.
 *
 * @param status the outcome
 * @param reason what the narrative says about a payment that did not complete; {@code null} when it
 *     completed
 */
public record Ending(Status status, String reason) {

  /** The outcomes a payment can end with. */
  public enum Status {
    /** Paid. */
    COMPLETED,
    /** Did not pay; nothing was left half-done. */
    FAILED,
    /** Something needs a person to look at it. */
    NEEDS_REVIEW
  }
}

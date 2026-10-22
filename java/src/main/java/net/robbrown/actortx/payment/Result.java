package net.robbrown.actortx.payment;

import java.util.List;

/**
 * How a transaction ended.
 *
 * <ul>
 *   <li>{@link Completed}: paid and settled
 *   <li>{@link Failed}: stopped cleanly; nothing is left half done
 *   <li>{@link NeedsReview}: the system will not guess; a person decides (see the review queue)
 * </ul>
 */
public sealed interface Result {

  /** Paid and settled. */
  record Completed(Summary summary) implements Result {}

  /** Stopped cleanly, with why. */
  record Failed(String reason) implements Result {}

  /** Waiting for a person, with why. */
  record NeedsReview(String reason) implements Result {}

  /** What a completed transaction came to. */
  record Summary(
      String id,
      String provider,
      long rate,
      long fee,
      long net,
      List<String> steps,
      String policy,
      GuaranteeOutcome guarantee) {}

  /**
   * The standing guarantee's verdict on a completed transaction.
   *
   * @param merchantId the merchant
   * @param period the guarantee period
   * @param margin achieved net minus guaranteed net: positive is margin, negative exposure
   */
  record GuaranteeOutcome(String merchantId, String period, long margin) {}

  /** The phase this result puts the transaction in. */
  default Phase phase() {
    return switch (this) {
      case Completed completed -> Phase.COMPLETED;
      case Failed failed -> Phase.FAILED;
      case NeedsReview review -> Phase.NEEDS_REVIEW;
    };
  }
}

package net.robbrown.actortx.economics;

/**
 * Running totals of finished transactions: how many, how each ended, what they earned and what they
 * cost. Immutable: a sum yields a new value.
 *
 * @param transactions how many transactions
 * @param completed how many completed
 * @param failed how many failed
 * @param needsReview how many were parked for review
 * @param value the value earned, summed
 * @param cost the cost, summed
 */
public record Totals(
    long transactions, long completed, long failed, long needsReview, Value value, Cost cost) {

  /** No transactions yet. */
  public static final Totals BLANK = new Totals(0L, 0L, 0L, 0L, Value.ZERO, Cost.ZERO);

  /** The totals of a single transaction that ended with {@code status}. */
  public static Totals of(Outcome.Status status, Value value, Cost cost) {
    return new Totals(
        1L,
        count(status, Outcome.Status.COMPLETED),
        count(status, Outcome.Status.FAILED),
        count(status, Outcome.Status.NEEDS_REVIEW),
        value,
        cost);
  }

  /** The sum of these totals and another. */
  public Totals plus(Totals other) {
    return new Totals(
        transactions + other.transactions,
        completed + other.completed,
        failed + other.failed,
        needsReview + other.needsReview,
        value.plus(other.value),
        cost.plus(other.cost));
  }

  private static long count(Outcome.Status status, Outcome.Status target) {
    return status == target ? 1L : 0L;
  }
}

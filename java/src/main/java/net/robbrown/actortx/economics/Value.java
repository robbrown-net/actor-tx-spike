package net.robbrown.actortx.economics;

/**
 * What a transaction earned, in minor units. Only a completed transaction earns value; the rest
 * earn {@link #ZERO}.
 *
 * @param savedVsMedian the net obtained minus the median quote's net (negative: paid for speed)
 * @param savedVsWorst the net obtained minus the worst quote's net
 * @param regretVsBest the best quote's net minus the net obtained (the price of non-price goals)
 * @param margin against the merchant's standing guarantee (negative is exposure)
 * @param providerFees the provider fees, reported and not subtracted again
 */
public record Value(
    long savedVsMedian, long savedVsWorst, long regretVsBest, long margin, long providerFees) {

  /** No value: what a transaction that did not complete earns. */
  public static final Value ZERO = new Value(0L, 0L, 0L, 0L, 0L);

  /** The value of one finished transaction. */
  public static Value of(Outcome outcome) {
    if (outcome.status() != Outcome.Status.COMPLETED) {
      return ZERO;
    }
    return outcome.discovery() == null ? withoutDiscovery(outcome) : withDiscovery(outcome);
  }

  /** The sum of this value and another, component by component. */
  public Value plus(Value other) {
    return new Value(
        savedVsMedian + other.savedVsMedian,
        savedVsWorst + other.savedVsWorst,
        regretVsBest + other.regretVsBest,
        margin + other.margin,
        providerFees + other.providerFees);
  }

  private static Value withoutDiscovery(Outcome outcome) {
    return new Value(0L, 0L, 0L, outcome.margin(), outcome.providerFee());
  }

  private static Value withDiscovery(Outcome outcome) {
    Discovery d = outcome.discovery();
    return new Value(
        d.chosenNet() - d.medianNet(),
        d.chosenNet() - d.worstNet(),
        d.bestNet() - d.chosenNet(),
        outcome.margin(),
        outcome.providerFee());
  }
}

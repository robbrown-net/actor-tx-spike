package net.robbrown.actortx.economics;

/**
 * The report on a set of transactions: the totals, the value against its baselines, and the net
 * after the cost of running. Cost and platform are in pico-units; value is in minor units, which is
 * why the benefit is converted with {@link Costs#PICOS_PER_MINOR}.
 *
 * @param transactions how many transactions
 * @param completed how many completed
 * @param failed how many failed
 * @param needsReview how many were parked for review
 * @param savedVsMedian the value saved against the median quote, in minor units
 * @param savedVsWorst the value saved against the worst quote, in minor units
 * @param regretVsBest the regret against the best quote, in minor units
 * @param margin the guarantee margin, in minor units
 * @param providerFees the provider fees, in minor units
 * @param cost the variable cost of running, in pico-units
 * @param platformPicos the platform cost of the elapsed time, in pico-units
 * @param benefitPicos the benefit (saved vs median plus margin) in pico-units
 * @param netPicos the benefit less the variable and platform costs, in pico-units
 */
public record Figures(
    long transactions,
    long completed,
    long failed,
    long needsReview,
    long savedVsMedian,
    long savedVsWorst,
    long regretVsBest,
    long margin,
    long providerFees,
    Cost cost,
    long platformPicos,
    long benefitPicos,
    long netPicos) {

  /**
   * The figures of some totals, with a platform cost for the elapsed time (zero for a single
   * provider, whose platform time is not attributed to it).
   */
  public static Figures of(Totals totals, long platformPicos) {
    Value v = totals.value();
    long benefit = Math.multiplyExact(v.savedVsMedian() + v.margin(), Costs.PICOS_PER_MINOR);
    long net = benefit - totals.cost().total() - platformPicos;
    return new Figures(
        totals.transactions(),
        totals.completed(),
        totals.failed(),
        totals.needsReview(),
        v.savedVsMedian(),
        v.savedVsWorst(),
        v.regretVsBest(),
        v.margin(),
        v.providerFees(),
        totals.cost(),
        platformPicos,
        benefit,
        net);
  }
}

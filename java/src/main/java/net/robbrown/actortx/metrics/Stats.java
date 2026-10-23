package net.robbrown.actortx.metrics;

import java.util.Collection;
import java.util.List;

/** Small statistics over collections of numbers: nearest-rank percentiles, means and extremes. */
public final class Stats {

  private Stats() {}

  /**
   * Percentiles, the maximum and the count of a set of values.
   *
   * @param p50 the median
   * @param p95 the 95th percentile
   * @param p99 the 99th percentile
   * @param max the largest value, or 0 for none
   * @param count how many values there were
   */
  public record Summary(double p50, double p95, double p99, double max, long count) {}

  /**
   * The nearest-rank percentile: always one of the values itself. The input is not changed.
   *
   * @param values the values, in any order
   * @param p the percentile, from 0 to 100
   * @return the value at that rank, or 0 for no values
   */
  public static double percentile(Collection<Double> values, double p) {
    if (values.isEmpty()) {
      return 0.0;
    }
    List<Double> sorted = values.stream().sorted().toList();
    int rank = (int) Math.max(Math.ceil(p / 100 * sorted.size()), 1);
    return sorted.get(rank - 1);
  }

  /**
   * The p50, p95, p99, maximum and count of some values.
   *
   * @param values the values, in any order
   * @return the summary; all zeros for no values
   */
  public static Summary summary(Collection<Double> values) {
    return new Summary(
        percentile(values, 50),
        percentile(values, 95),
        percentile(values, 99),
        max(values),
        values.size());
  }

  /**
   * The arithmetic mean.
   *
   * @param values the values
   * @return the mean, or 0 for no values
   */
  public static double mean(Collection<Double> values) {
    return values.stream().mapToDouble(v -> v).average().orElse(0.0);
  }

  /**
   * The largest value.
   *
   * @param values the values
   * @return the maximum, or 0 for no values
   */
  public static double max(Collection<Double> values) {
    return values.stream().mapToDouble(v -> v).max().orElse(0.0);
  }

  /**
   * The total.
   *
   * @param values the values
   * @return the sum, or 0 for no values
   */
  public static double sum(Collection<Double> values) {
    return values.stream().mapToDouble(v -> v).sum();
  }
}

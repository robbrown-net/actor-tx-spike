package net.robbrown.actortx.bulk;

import java.util.List;

/**
 * Latency percentiles, by the nearest-rank method. All zero when there is nothing to measure.
 *
 * @param p50 the median
 * @param p90 the 90th percentile
 * @param p99 the 99th percentile
 * @param max the largest
 */
public record Percentiles(long p50, long p90, long p99, long max) {

  /** The percentiles of these values (any order; none is fine). */
  public static Percentiles of(List<Long> values) {
    if (values.isEmpty()) {
      return new Percentiles(0, 0, 0, 0);
    }
    List<Long> sorted = values.stream().sorted().toList();
    return new Percentiles(
        rank(sorted, 50), rank(sorted, 90), rank(sorted, 99), sorted.get(sorted.size() - 1));
  }

  private static long rank(List<Long> sorted, int percent) {
    int index = (int) Math.ceil(percent / 100.0 * sorted.size()) - 1;
    return sorted.get(Math.max(index, 0));
  }
}

package net.robbrown.actortx.metrics;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * One-minute rollups of raw samples: the long-term tier of the retention design (raw, then one
 * minute, then one hour). Each bucket keeps the few numbers needed to chart a long window without
 * keeping every second.
 */
public final class Rollup {

  /** Milliseconds in a minute. */
  public static final long MINUTE_MS = 60_000L;

  private Rollup() {}

  /**
   * The start of the minute a moment falls in.
   *
   * @param atMs wall-clock milliseconds
   * @return the minute's first millisecond
   */
  public static long minute(long atMs) {
    return Math.floorDiv(atMs, MINUTE_MS) * MINUTE_MS;
  }

  /**
   * A new, empty bucket for the minute containing a moment.
   *
   * @param atMs wall-clock milliseconds
   * @return the bucket, not yet holding any sample
   */
  public static Bucket open(long atMs) {
    return new Bucket(minute(atMs));
  }

  /** An accumulating minute. Not thread-safe: the owner folds samples in one at a time. */
  public static final class Bucket {

    private final long at;
    private final Map<Series, List<Double>> values = new EnumMap<>(Series.class);
    private int samples;

    private Bucket(long at) {
      this.at = at;
    }

    /**
     * The first millisecond of the minute.
     *
     * @return the minute's start
     */
    public long at() {
      return at;
    }

    /**
     * Fold a sample into the minute.
     *
     * @param sample the sample, taken during this minute
     */
    public void add(Sample sample) {
      samples++;
      for (Series series : Series.values()) {
        values.computeIfAbsent(series, k -> new ArrayList<>()).add(series.readFrom(sample));
      }
    }

    /**
     * The compact, chartable form of the minute.
     *
     * @return the means, maxima and sums of the samples folded in
     */
    public MinuteRollup finish() {
      return new MinuteRollup(
          at,
          samples,
          Stats.mean(of(Series.HEAP_USED)),
          Stats.max(of(Series.HEAP_USED)),
          Stats.max(of(Series.THREADS)),
          Stats.mean(of(Series.GC_PER_SECOND)),
          Math.round(Stats.sum(of(Series.COMPLETED))),
          Math.round(Stats.sum(of(Series.FAILED))),
          Math.round(Stats.sum(of(Series.NEEDS_REVIEW))),
          Stats.max(of(Series.E2E_P95)));
    }

    private List<Double> of(Series series) {
      return values.getOrDefault(series, List.of());
    }
  }

  /** The series a minute keeps, each read from one sample. */
  private enum Series {
    HEAP_USED(s -> s.jvm().heapUsed()),
    THREADS(s -> s.jvm().threads()),
    GC_PER_SECOND(s -> s.gcPerSecond()),
    COMPLETED(s -> s.tx().completed()),
    FAILED(s -> s.tx().failed()),
    NEEDS_REVIEW(s -> s.tx().needsReview()),
    E2E_P95(s -> s.e2e().p95());

    private final ToDoubleFunction<Sample> reader;

    Series(ToDoubleFunction<Sample> reader) {
      this.reader = reader;
    }

    double readFrom(Sample sample) {
      return reader.applyAsDouble(sample);
    }
  }
}

package net.robbrown.actortx.metrics;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.robbrown.actortx.events.Event;

/**
 * Resource and application metrics: the "how much does it cost" half of observability. The events
 * are the narrative; the metrics fold them into counts, latency percentiles and the latest health
 * of each provider, and sample the JVM.
 *
 * <p>Subscribe {@link #onEvent(Event)} to the event bus ({@code bus.subscribe(metrics::onEvent)}),
 * then call {@link #sample()} on a schedule (or {@link #schedule}). Both the raw samples and the
 * minute rollups are bounded, so monitoring cannot become the leak. All methods are thread-safe.
 */
public final class Metrics {

  /** How many raw samples the default instance keeps. */
  public static final int DEFAULT_RAW_CAPACITY = 900;

  /** How many minute rollups the default instance keeps. */
  public static final int DEFAULT_MINUTE_CAPACITY = 1_440;

  /** How many raw samples {@link #snapshot()} returns. */
  public static final int SNAPSHOT_LIMIT = 300;

  private static final int E2E_WINDOW = 500;
  private static final String PAYMENT = "payment";
  private static final String HEALTH = "health";
  private static final Map<String, Counter> TERMINAL =
      Map.of(
          "tx_completed", Counter.COMPLETED,
          "tx_failed", Counter.FAILED,
          "needs_review", Counter.NEEDS_REVIEW);

  private final LongSupplier clock;
  private final Supplier<JvmStats> jvm;
  private final int rawCapacity;
  private final int minuteCapacity;
  private final Deque<Sample> raw = new ArrayDeque<>();
  private final Deque<MinuteRollup> minutes = new ArrayDeque<>();
  private final Map<String, Long> starts = new HashMap<>();
  private final Deque<Double> durations = new ArrayDeque<>();
  private final EnumMap<Counter, Long> counts = new EnumMap<>(Counter.class);
  private final Map<String, ProviderHealth> providers = new HashMap<>();
  private Rollup.Bucket bucket;
  private Reading previous;

  /** Metrics with the system clock, the JVM's own figures and the default capacities. */
  public Metrics() {
    this(
        System::currentTimeMillis,
        JvmStats::current,
        DEFAULT_RAW_CAPACITY,
        DEFAULT_MINUTE_CAPACITY);
  }

  /**
   * Metrics with an injected clock and JVM reader, for tests and for the lead's own wiring.
   *
   * @param clock the wall clock, in milliseconds
   * @param jvm reads the JVM's figures
   * @param rawCapacity how many raw samples to keep; at least 1
   * @param minuteCapacity how many minute rollups to keep; at least 1
   */
  public Metrics(LongSupplier clock, Supplier<JvmStats> jvm, int rawCapacity, int minuteCapacity) {
    if (rawCapacity < 1 || minuteCapacity < 1) {
      throw new IllegalArgumentException("capacities must be at least 1");
    }
    this.clock = clock;
    this.jvm = jvm;
    this.rawCapacity = rawCapacity;
    this.minuteCapacity = minuteCapacity;
    for (Counter counter : Counter.values()) {
      counts.put(counter, 0L);
    }
  }

  /**
   * Fold one event into the counters: transactions received, ended and failed over, their
   * end-to-end latency, and the latest health of each provider.
   *
   * @param event the event, as the bus delivers it
   */
  public synchronized void onEvent(Event event) {
    if (PAYMENT.equals(event.actor())) {
      applyPayment(event);
    }
    if (HEALTH.equals(event.kind())) {
      applyHealth(event);
    }
  }

  private void applyPayment(Event event) {
    if ("tx_received".equals(event.kind())) {
      starts.put(event.txId(), event.atMs());
      bump(Counter.STARTED);
    } else if (TERMINAL.containsKey(event.kind())) {
      finish(event, TERMINAL.get(event.kind()));
    } else if ("failover".equals(event.kind())) {
      bump(Counter.FAILOVERS);
    }
  }

  private void finish(Event event, Counter outcome) {
    Long started = starts.remove(event.txId());
    if (started != null) {
      durations.addFirst((double) (event.atMs() - started));
      if (durations.size() > E2E_WINDOW) {
        durations.removeLast();
      }
    }
    bump(outcome);
  }

  private void applyHealth(Event event) {
    Object provider = event.data().get("provider");
    if (provider != null) {
      providers.put(String.valueOf(provider), ProviderHealth.from(event.data()));
    }
  }

  private void bump(Counter counter) {
    counts.put(counter, counts.get(counter) + 1);
  }

  /**
   * Take a sample now: the JVM, the rates and transaction counts since the previous sample, and the
   * latency and provider health as they stand. It goes into the raw ring and into the current
   * minute.
   *
   * @return the sample just taken
   */
  public synchronized Sample sample() {
    long started = System.nanoTime();
    Reading current = reading();
    Reading before = previous == null ? current : previous;
    Sample sample =
        new Sample(
            current.at(),
            current.jvm(),
            gcPerSecond(current, before),
            txCounts(current, before),
            Stats.summary(durations),
            Map.copyOf(providers),
            (System.nanoTime() - started) / 1_000L);
    previous = current;
    store(sample);
    return sample;
  }

  /**
   * Sample on a schedule, the way a timer would. Stops when the returned future is cancelled.
   *
   * @param executor runs the sampling
   * @param interval the time between samples
   * @return the scheduled task
   */
  public ScheduledFuture<?> schedule(ScheduledExecutorService executor, Duration interval) {
    long millis = interval.toMillis();
    return executor.scheduleAtFixedRate(() -> sample(), millis, millis, TimeUnit.MILLISECONDS);
  }

  private Reading reading() {
    return new Reading(clock.getAsLong(), jvm.get(), new EnumMap<>(counts));
  }

  private static long gcPerSecond(Reading current, Reading before) {
    double seconds = Math.max(current.at() - before.at(), 1) / 1_000.0;
    return Math.round((current.jvm().gcCount() - before.jvm().gcCount()) / seconds);
  }

  private TxCounts txCounts(Reading current, Reading before) {
    return new TxCounts(
        delta(current, before, Counter.STARTED),
        delta(current, before, Counter.COMPLETED),
        delta(current, before, Counter.FAILED),
        delta(current, before, Counter.NEEDS_REVIEW),
        delta(current, before, Counter.FAILOVERS),
        starts.size());
  }

  private static long delta(Reading current, Reading before, Counter counter) {
    return current.counts().get(counter) - before.counts().get(counter);
  }

  private void store(Sample sample) {
    raw.addLast(sample);
    if (raw.size() > rawCapacity) {
      raw.removeFirst();
    }
    roll(sample);
  }

  // Fold the sample into the minute it belongs to, closing the previous minute when it has ended.
  private void roll(Sample sample) {
    if (bucket == null) {
      bucket = Rollup.open(sample.at());
    } else if (bucket.at() != Rollup.minute(sample.at())) {
      pushMinute(bucket.finish());
      bucket = Rollup.open(sample.at());
    }
    bucket.add(sample);
  }

  private void pushMinute(MinuteRollup closed) {
    minutes.addLast(closed);
    if (minutes.size() > minuteCapacity) {
      minutes.removeFirst();
    }
  }

  /**
   * What the metrics hold: the most recent {@link #SNAPSHOT_LIMIT} raw samples, every minute
   * rollup, and the latest sample.
   *
   * @return an immutable copy
   */
  public Snapshot snapshot() {
    return snapshot(SNAPSHOT_LIMIT);
  }

  /**
   * What the metrics hold, with at most {@code limit} of the most recent raw samples.
   *
   * @param limit how many raw samples to return; not negative
   * @return an immutable copy
   */
  public synchronized Snapshot snapshot(int limit) {
    if (limit < 0) {
      throw new IllegalArgumentException("limit must not be negative");
    }
    List<Sample> all = List.copyOf(raw);
    List<Sample> recent = List.copyOf(all.subList(Math.max(0, all.size() - limit), all.size()));
    Sample latest = recent.isEmpty() ? null : recent.get(recent.size() - 1);
    return new Snapshot(recent, List.copyOf(minutes), latest);
  }

  /** The counters at one moment, with the wall clock and the JVM. */
  private record Reading(long at, JvmStats jvm, Map<Counter, Long> counts) {}

  /** The application counters kept cumulatively and differenced per sample. */
  private enum Counter {
    STARTED,
    COMPLETED,
    FAILED,
    NEEDS_REVIEW,
    FAILOVERS
  }
}

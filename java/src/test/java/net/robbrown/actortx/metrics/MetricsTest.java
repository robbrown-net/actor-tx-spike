package net.robbrown.actortx.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.robbrown.actortx.events.Event;
import org.junit.jupiter.api.Test;

class MetricsTest {

  private static final long START = 1_700_000_000_000L;

  private final AtomicLong clock = new AtomicLong(START);
  private final AtomicReference<JvmStats> jvm =
      new AtomicReference<>(new JvmStats(1_000, 4_000, 8, 0));

  private Metrics metrics() {
    return metrics(900, 1_440);
  }

  private Metrics metrics(int rawCapacity, int minuteCapacity) {
    return new Metrics(clock::get, jvm::get, rawCapacity, minuteCapacity);
  }

  private void advance(long ms) {
    clock.addAndGet(ms);
  }

  private static Event event(
      String kind, String tx, String actor, Map<String, Object> data, long at) {
    return new Event(1, at, tx, actor, kind, "m", data);
  }

  private static Event event(String kind, String tx, String actor, long at) {
    return event(kind, tx, actor, Map.of(), at);
  }

  @Test
  void aSampleHasJvmTransactionAndLatencySections() {
    Metrics metrics = new Metrics();
    metrics.sample();
    Sample second = metrics.sample();

    assertThat(second.jvm().heapUsed()).isPositive();
    assertThat(second.jvm().threads()).isPositive();
    assertThat(second.gcPerSecond()).isNotNegative();
    assertThat(second.overheadUs()).isNotNegative();
    assertThat(second.tx()).isEqualTo(new TxCounts(0, 0, 0, 0, 0, 0));
    assertThat(second.e2e().count()).isZero();
  }

  @Test
  void applicationCountersAndLatencyComeFromTheEvents() {
    Metrics metrics = metrics();
    metrics.sample();

    metrics.onEvent(event("tx_received", "t1", "payment", 1_000));
    metrics.onEvent(event("tx_received", "t2", "payment", 1_000));
    metrics.onEvent(event("failover", "t1", "payment", 0));
    metrics.onEvent(event("tx_completed", "t1", "payment", 1_250));
    metrics.onEvent(event("tx_failed", "t2", "payment", 1_900));
    metrics.onEvent(event("tx_completed", "unknown-start", "payment", 2_000));
    metrics.onEvent(event("needs_review", "t3", "payment", 2_000));
    metrics.onEvent(event("received", "t1", "provider:a", 0));
    advance(1_000);
    Sample s = metrics.sample();

    assertThat(s.tx()).isEqualTo(new TxCounts(2, 2, 1, 1, 1, 0));
    assertThat(s.e2e().count()).isEqualTo(2);
    assertThat(s.e2e().max()).isEqualTo(900.0);
    assertThat(s.e2e().p50()).isEqualTo(250.0);
  }

  @Test
  void providerHealthEventsAreKeptAsTheLatestKnownState() {
    Metrics metrics = metrics();
    Map<String, Object> data =
        Map.of(
            "provider", "alpha",
            "state", "degraded",
            "p95_ms", 90,
            "error_rate", 0.3,
            "in_flight", 2,
            "breaker", "closed",
            "extra", "ignored");

    metrics.onEvent(event("health", null, "provider:alpha", data, 0));
    metrics.onEvent(
        event(
            "health", null, "provider:alpha", Map.of("provider", "alpha", "state", "healthy"), 0));
    advance(1_000);
    Sample s = metrics.sample();

    assertThat(s.providers())
        .isEqualTo(Map.of("alpha", new ProviderHealth("healthy", 0.0, 0.0, 0, "unknown")));
  }

  @Test
  void aHealthEventWithAllItsFieldsIsReadInFull() {
    Metrics metrics = metrics();
    Map<String, Object> data =
        Map.of(
            "provider", "alpha",
            "state", "degraded",
            "p95_ms", 90,
            "error_rate", 0.3,
            "in_flight", 2,
            "breaker", "closed",
            "extra", "ignored");

    metrics.onEvent(event("health", null, "provider:alpha", data, 0));

    assertThat(metrics.sample().providers())
        .isEqualTo(Map.of("alpha", new ProviderHealth("degraded", 90.0, 0.3, 2, "closed")));
  }

  @Test
  void unrelatedEventsAreIgnored() {
    Metrics metrics = metrics();

    metrics.onEvent(event("step_started", "t1", "payment", 0));
    metrics.onEvent(event("health", null, "provider:beta", Map.of("state", "down"), 0));
    metrics.onEvent(event("tx_completed", "t9", "selector", 0));
    Sample s = metrics.sample();

    assertThat(s.tx()).isEqualTo(new TxCounts(0, 0, 0, 0, 0, 0));
    assertThat(s.providers()).isEmpty();
  }

  @Test
  void rawSamplesAreBoundedAndSnapshotsCanBeLimited() {
    Metrics metrics = metrics(3, 1_440);

    for (int i = 0; i < 5; i++) {
      advance(1_000);
      metrics.sample();
    }

    Snapshot snap = metrics.snapshot();
    assertThat(snap.raw()).hasSize(3);
    assertThat(snap.latest()).isEqualTo(snap.raw().get(2));
    assertThat(metrics.snapshot(2).raw()).hasSize(2);
    assertThat(metrics.snapshot(0).latest()).isNull();
  }

  @Test
  void minuteRollupsAreProducedWhenTheMinuteChangesAndAreBounded() {
    Metrics metrics = metrics(900, 2);

    for (int i = 0; i < 4; i++) {
      metrics.sample();
      advance(61_000);
    }
    metrics.sample();
    var minutes = metrics.snapshot().minutes();

    assertThat(minutes).hasSize(2);
    assertThat(minutes).allMatch(m -> m.samples() >= 1 && m.heapUsedMax() > 0);
  }

  @Test
  void samplesWithinOneMinuteShareABucket() {
    Metrics metrics = metrics(900, 2);

    metrics.sample();
    advance(1_000);
    metrics.sample();
    advance(61_000);
    metrics.sample();

    assertThat(metrics.snapshot().minutes())
        .singleElement()
        .extracting(MinuteRollup::samples)
        .isEqualTo(2L);
  }

  @Test
  void gcRateIsDerivedFromTheCollectionCount() {
    Metrics metrics = metrics();
    metrics.sample();
    jvm.set(new JvmStats(1_000, 4_000, 8, 14));
    advance(2_000);

    assertThat(metrics.sample().gcPerSecond()).isEqualTo(7);
  }

  @Test
  void aSampleTakenWithoutElapsedTimeCountsAsOneMillisecond() {
    Metrics metrics = metrics();
    metrics.sample();
    jvm.set(new JvmStats(1_000, 4_000, 8, 5));

    assertThat(metrics.sample().gcPerSecond()).isEqualTo(5_000);
  }

  @Test
  void theLatencyWindowKeepsTheLatestDurations() {
    Metrics metrics = metrics();
    for (int i = 0; i < 501; i++) {
      metrics.onEvent(event("tx_received", "t" + i, "payment", 0));
      metrics.onEvent(event("tx_completed", "t" + i, "payment", 10));
    }

    assertThat(metrics.sample().e2e().count()).isEqualTo(500);
  }

  @Test
  void theTimerTicksOnItsOwnWhenAnIntervalIsGiven() throws InterruptedException {
    Metrics metrics = new Metrics();
    ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    try {
      metrics.schedule(executor, Duration.ofMillis(20));

      Thread.sleep(120);
      assertThat(metrics.snapshot().raw().size()).isGreaterThanOrEqualTo(2);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void theDefaultInstanceIsUsableFromTheStart() {
    Snapshot snap = new Metrics().snapshot();

    assertThat(snap.raw()).isEmpty();
    assertThat(snap.minutes()).isEmpty();
    assertThat(snap.latest()).isNull();
  }

  @Test
  void invalidCapacitiesAndLimitsAreRefused() {
    assertThatThrownBy(() -> metrics(0, 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> metrics(1, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> metrics().snapshot(-1)).isInstanceOf(IllegalArgumentException.class);
  }
}

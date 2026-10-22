package net.robbrown.actortx.provider;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Immutable model of our view of one provider's health. No clocks and no processes: time is passed
 * in, so every transition is deterministic.
 *
 * <p>The circuit breaker goes closed, then open after enough consecutive failures, then half-open
 * after the cool-down (one probe admitted), then closed on a successful probe or open again on a
 * failed one. After a successful probe the provider stays degraded until a streak of successes.
 */
public final class Health {

  private static final Stats EMPTY_STATS = new Stats(0, 0.0, 0.0, 0.0);
  private static final double P95 = 0.95;

  private final HealthConfig config;
  private final Breaker breaker;
  private final boolean probing;
  private final long openedAt;
  private final int consecutiveFailures;
  private final List<Sample> window;
  private final int recovering;
  private final String version;
  private final Drift drift;
  private final boolean quarantined;

  private Health(Builder builder) {
    this.config = builder.config;
    this.breaker = builder.breaker;
    this.probing = builder.probing;
    this.openedAt = builder.openedAt;
    this.consecutiveFailures = builder.consecutiveFailures;
    this.window = builder.window;
    this.recovering = builder.recovering;
    this.version = builder.version;
    this.drift = builder.drift;
    this.quarantined = builder.quarantined;
  }

  /** Result of asking whether a call may be made now. */
  public sealed interface Checkout permits Checkout.Admitted, Checkout.Refused {

    /** The call may proceed; {@code health} carries any state change, such as an admitted probe. */
    record Admitted(Health health) implements Checkout {}

    /** The call must not be made. */
    record Refused(Refusal reason) implements Checkout {}
  }

  /** Why a call was refused. */
  public enum Refusal {
    QUARANTINED,
    CIRCUIT_OPEN
  }

  /** A version change that quarantined the provider. */
  public record Drift(String from, String to) {}

  /** The health after observing a version, and what the observation showed. */
  public record VersionObservation(Health health, Verdict verdict) {}

  /** What a version observation showed. */
  public sealed interface Verdict permits Verdict.FirstSeen, Verdict.Unchanged, Verdict.Changed {

    /** The first version ever reported. */
    record FirstSeen() implements Verdict {}

    /** The same version as before. */
    record Unchanged() implements Verdict {}

    /** A different version: the provider is quarantined. */
    record Changed(String from, String to) implements Verdict {}
  }

  /** One call in the rolling window. */
  record Sample(Outcome outcome, long latencyMs) {}

  /** A health model with no observations yet. */
  public static Health create(HealthConfig config) {
    return new Builder(config).build();
  }

  /** May a call be made now? Admitting a half-open probe is a state change. */
  public Checkout checkout(long nowMs) {
    return quarantined ? refused(Refusal.QUARANTINED) : checkoutBreaker(nowMs);
  }

  /** Record the outcome and latency of a completed call. */
  public Health record(Outcome outcome, long latencyMs, long nowMs) {
    Objects.requireNonNull(outcome, "outcome");
    List<Sample> next =
        Stream.concat(Stream.of(new Sample(outcome, latencyMs)), window.stream())
            .limit(config.windowSize())
            .toList();
    return toBuilder().window(next).build().advance(outcome.failure(), nowMs);
  }

  /** A probe was admitted but the caller vanished: free the probe slot. */
  public Health abandon() {
    return toBuilder().probing(false).build();
  }

  /** The derived state. Never {@link HealthState#DOWN}, which is decided outside this model. */
  public HealthState state() {
    return Stream.of(
            when(quarantined, HealthState.QUARANTINED),
            when(breaker == Breaker.OPEN, HealthState.STALLED),
            when(breaker == Breaker.HALF_OPEN, HealthState.DEGRADED),
            when(window.isEmpty(), HealthState.UNKNOWN),
            when(recovering > 0, HealthState.DEGRADED))
        .flatMap(Optional::stream)
        .findFirst()
        .orElseGet(this::measuredState);
  }

  /** Summary of the rolling window. */
  public Stats stats() {
    return window.isEmpty() ? EMPTY_STATS : summarize();
  }

  /** Observe a version reported by a probe. A change quarantines the provider. */
  public VersionObservation observeVersion(String reported) {
    if (version == null) {
      return new VersionObservation(toBuilder().version(reported).build(), new Verdict.FirstSeen());
    }
    if (Objects.equals(version, reported)) {
      return new VersionObservation(this, new Verdict.Unchanged());
    }
    return drifted(reported);
  }

  /** Lift the quarantine after the drift has been reviewed. */
  public Health release() {
    return toBuilder().quarantined(false).drift(null).build();
  }

  /** The circuit breaker state. */
  public Breaker breaker() {
    return breaker;
  }

  /** The last version observed, or null if none. */
  public String version() {
    return version;
  }

  /** Successes still needed before the provider counts as healthy again. */
  public int recovering() {
    return recovering;
  }

  /** True while quarantined by version drift. */
  public boolean quarantined() {
    return quarantined;
  }

  /** True while a half-open probe is in flight. */
  public boolean probing() {
    return probing;
  }

  /** The last version change, or null when none is pending review. */
  public Drift drift() {
    return drift;
  }

  /** When the breaker last opened (meaningful only while open). */
  long openedAt() {
    return openedAt;
  }

  /** Consecutive failures in the current streak. */
  int consecutiveFailures() {
    return consecutiveFailures;
  }

  /** The rolling window, newest first. */
  List<Sample> window() {
    return window;
  }

  private Checkout checkoutBreaker(long nowMs) {
    if (breaker == Breaker.OPEN) {
      return checkoutOpen(nowMs);
    }
    return breaker == Breaker.HALF_OPEN ? checkoutHalfOpen() : admit(this);
  }

  private Checkout checkoutOpen(long nowMs) {
    if (nowMs - openedAt < config.coolDownMs()) {
      return refused(Refusal.CIRCUIT_OPEN);
    }
    return admit(toBuilder().breaker(Breaker.HALF_OPEN).probing(true).build());
  }

  private Checkout checkoutHalfOpen() {
    return probing ? refused(Refusal.CIRCUIT_OPEN) : admit(toBuilder().probing(true).build());
  }

  private static Checkout admit(Health health) {
    return new Checkout.Admitted(health);
  }

  private static Checkout refused(Refusal reason) {
    return new Checkout.Refused(reason);
  }

  private Health advance(boolean failed, long nowMs) {
    if (breaker == Breaker.OPEN) {
      return this;
    }
    return breaker == Breaker.HALF_OPEN ? afterProbe(failed, nowMs) : afterCall(failed, nowMs);
  }

  private Health afterProbe(boolean failed, long nowMs) {
    return failed ? trip(nowMs) : closeAfterProbe();
  }

  private Health afterCall(boolean failed, long nowMs) {
    return failed ? failClosed(nowMs) : succeedClosed();
  }

  private Health closeAfterProbe() {
    return toBuilder()
        .breaker(Breaker.CLOSED)
        .probing(false)
        .consecutiveFailures(0)
        .openedAt(0L)
        .window(window.stream().limit(1).toList())
        .recovering(Math.max(config.recoverySuccesses() - 1, 0))
        .build();
  }

  private Health failClosed(long nowMs) {
    int failures = consecutiveFailures + 1;
    Health next =
        toBuilder()
            .consecutiveFailures(failures)
            .recovering(recovering > 0 ? config.recoverySuccesses() : 0)
            .build();
    return failures >= config.failureThreshold() ? next.trip(nowMs) : next;
  }

  private Health succeedClosed() {
    return toBuilder().consecutiveFailures(0).recovering(Math.max(recovering - 1, 0)).build();
  }

  private Health trip(long nowMs) {
    return toBuilder().breaker(Breaker.OPEN).probing(false).openedAt(nowMs).build();
  }

  private HealthState measuredState() {
    Stats stats = stats();
    boolean degraded =
        stats.errorRate() >= config.degradedErrorRate() || stats.p95Ms() >= config.slowMs();
    return degraded ? HealthState.DEGRADED : HealthState.HEALTHY;
  }

  private Stats summarize() {
    int n = window.size();
    long[] sorted = window.stream().mapToLong(Sample::latencyMs).sorted().toArray();
    long failures = window.stream().filter(s -> s.outcome().failure()).count();
    double meanMs = (double) Arrays.stream(sorted).sum() / n;
    double p95Ms = sorted[(int) Math.ceil(P95 * n) - 1];
    return new Stats(n, (double) failures / n, meanMs, p95Ms);
  }

  private VersionObservation drifted(String reported) {
    Health next =
        toBuilder().version(reported).quarantined(true).drift(new Drift(version, reported)).build();
    return new VersionObservation(next, new Verdict.Changed(version, reported));
  }

  private static Optional<HealthState> when(boolean condition, HealthState state) {
    return condition ? Optional.of(state) : Optional.empty();
  }

  private Builder toBuilder() {
    return new Builder(this);
  }

  /** Mutable copy used to build a changed {@link Health}. */
  private static final class Builder {
    private final HealthConfig config;
    private Breaker breaker = Breaker.CLOSED;
    private boolean probing;
    private long openedAt;
    private int consecutiveFailures;
    private List<Sample> window = List.of();
    private int recovering;
    private String version;
    private Drift drift;
    private boolean quarantined;

    Builder(HealthConfig config) {
      this.config = config;
    }

    Builder(Health health) {
      this.config = health.config;
      this.breaker = health.breaker;
      this.probing = health.probing;
      this.openedAt = health.openedAt;
      this.consecutiveFailures = health.consecutiveFailures;
      this.window = health.window;
      this.recovering = health.recovering;
      this.version = health.version;
      this.drift = health.drift;
      this.quarantined = health.quarantined;
    }

    Builder breaker(Breaker value) {
      this.breaker = value;
      return this;
    }

    Builder probing(boolean value) {
      this.probing = value;
      return this;
    }

    Builder openedAt(long value) {
      this.openedAt = value;
      return this;
    }

    Builder consecutiveFailures(int value) {
      this.consecutiveFailures = value;
      return this;
    }

    Builder window(List<Sample> value) {
      this.window = value;
      return this;
    }

    Builder recovering(int value) {
      this.recovering = value;
      return this;
    }

    Builder version(String value) {
      this.version = value;
      return this;
    }

    Builder drift(Drift value) {
      this.drift = value;
      return this;
    }

    Builder quarantined(boolean value) {
      this.quarantined = value;
      return this;
    }

    Health build() {
      return new Health(this);
    }
  }
}

package net.robbrown.actortx.provider;

/**
 * Thresholds for the health model. Start from {@link #defaults()} and tune with the {@code with}
 * methods.
 *
 * @param windowSize how many recent calls the rolling window keeps
 * @param failureThreshold consecutive failures that open the breaker
 * @param coolDownMs how long an open breaker refuses calls before admitting a probe
 * @param degradedErrorRate error rate at or above which the provider is degraded
 * @param slowMs p95 latency at or above which the provider is degraded
 * @param recoverySuccesses successes (the probe counts as one) needed before healthy again
 */
public record HealthConfig(
    int windowSize,
    int failureThreshold,
    long coolDownMs,
    double degradedErrorRate,
    long slowMs,
    int recoverySuccesses) {

  /** The defaults of the Elixir model. */
  public static HealthConfig defaults() {
    return new HealthConfig(20, 5, 30_000L, 0.2, 2_000L, 3);
  }

  /** A copy with another window size. */
  public HealthConfig withWindowSize(int value) {
    return new HealthConfig(
        value, failureThreshold, coolDownMs, degradedErrorRate, slowMs, recoverySuccesses);
  }

  /** A copy with another failure threshold. */
  public HealthConfig withFailureThreshold(int value) {
    return new HealthConfig(
        windowSize, value, coolDownMs, degradedErrorRate, slowMs, recoverySuccesses);
  }

  /** A copy with another cool-down. */
  public HealthConfig withCoolDownMs(long value) {
    return new HealthConfig(
        windowSize, failureThreshold, value, degradedErrorRate, slowMs, recoverySuccesses);
  }

  /** A copy with another degraded error rate. */
  public HealthConfig withDegradedErrorRate(double value) {
    return new HealthConfig(
        windowSize, failureThreshold, coolDownMs, value, slowMs, recoverySuccesses);
  }

  /** A copy with another slow threshold. */
  public HealthConfig withSlowMs(long value) {
    return new HealthConfig(
        windowSize, failureThreshold, coolDownMs, degradedErrorRate, value, recoverySuccesses);
  }

  /** A copy with another recovery streak length. */
  public HealthConfig withRecoverySuccesses(int value) {
    return new HealthConfig(
        windowSize, failureThreshold, coolDownMs, degradedErrorRate, slowMs, value);
  }
}

package net.robbrown.actortx.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import net.robbrown.actortx.provider.Health.Checkout;
import net.robbrown.actortx.provider.Health.Drift;
import net.robbrown.actortx.provider.Health.Refusal;
import net.robbrown.actortx.provider.Health.Verdict;
import net.robbrown.actortx.provider.Health.VersionObservation;
import org.junit.jupiter.api.Test;

class HealthTest {

  private static final HealthConfig DEFAULTS = HealthConfig.defaults();

  private static Health fail(Health health, int times, long nowMs) {
    return repeat(health, Outcome.ERROR, 10, nowMs, times);
  }

  private static Health repeat(
      Health health, Outcome outcome, long latencyMs, long nowMs, int times) {
    Health out = health;
    for (int i = 0; i < times; i++) {
      out = out.record(outcome, latencyMs, nowMs);
    }
    return out;
  }

  private static Health admittedHealth(Checkout checkout) {
    return ((Checkout.Admitted) checkout).health();
  }

  @Test
  void unknownWithNoObservations() {
    assertThat(Health.create(DEFAULTS).state()).isEqualTo(HealthState.UNKNOWN);
  }

  @Test
  void healthyAfterSuccessfulFastCallsRejectionsAreHealthyAnswers() {
    Health h =
        Health.create(DEFAULTS).record(Outcome.SUCCESS, 10, 0).record(Outcome.REJECTED, 20, 1);
    assertThat(h.state()).isEqualTo(HealthState.HEALTHY);
  }

  @Test
  void degradedOnElevatedErrorRateWithoutOpeningTheBreaker() {
    Health h =
        Health.create(DEFAULTS.withFailureThreshold(99))
            .record(Outcome.SUCCESS, 10, 0)
            .record(Outcome.ERROR, 10, 1);
    assertThat(h.state()).isEqualTo(HealthState.DEGRADED);
  }

  @Test
  void degradedWhenP95LatencyIsSlow() {
    Health h = Health.create(DEFAULTS.withSlowMs(100)).record(Outcome.SUCCESS, 500, 0);
    assertThat(h.state()).isEqualTo(HealthState.DEGRADED);
  }

  @Test
  void stalledWhenBreakerOpenDegradedWhenHalfOpen() {
    Health open = fail(Health.create(DEFAULTS.withFailureThreshold(2)), 2, 0);
    assertThat(open.state()).isEqualTo(HealthState.STALLED);

    Health half = admittedHealth(open.checkout(1_000_000));
    assertThat(half.state()).isEqualTo(HealthState.DEGRADED);
  }

  @Test
  void quarantinedWins() {
    Health drifted =
        Health.create(DEFAULTS).observeVersion("1").health().observeVersion("2").health();
    assertThat(drifted.state()).isEqualTo(HealthState.QUARANTINED);
  }

  @Test
  void anErrorRateExactlyAtTheThresholdIsDegraded() {
    Health h =
        Health.create(DEFAULTS.withFailureThreshold(99).withDegradedErrorRate(0.2))
            .record(Outcome.ERROR, 1, 0);
    h = repeat(h, Outcome.SUCCESS, 1, 0, 4);
    assertThat(h.stats().errorRate()).isEqualTo(0.2);
    assertThat(h.state()).isEqualTo(HealthState.DEGRADED);
  }

  @Test
  void justUnderTheThresholdIsHealthy() {
    Health h = Health.create(DEFAULTS.withFailureThreshold(99).withDegradedErrorRate(0.2));
    h = repeat(h, Outcome.SUCCESS, 1, 0, 6);
    h = h.record(Outcome.ERROR, 1, 0);
    // 1 error in 7 = 0.14
    assertThat(h.state()).isEqualTo(HealthState.HEALTHY);
  }

  @Test
  void latencyExactlyAtSlowMsIsDegraded() {
    Health atLimit = Health.create(DEFAULTS.withSlowMs(100)).record(Outcome.SUCCESS, 100, 0);
    Health under = Health.create(DEFAULTS.withSlowMs(100)).record(Outcome.SUCCESS, 99, 0);
    assertThat(atLimit.state()).isEqualTo(HealthState.DEGRADED);
    assertThat(under.state()).isEqualTo(HealthState.HEALTHY);
  }

  @Test
  void recordRejectsAnUnknownOutcome() {
    Health h = Health.create(DEFAULTS);
    // Elixir rejected unknown atoms; in Java the outcome is an enum, so only null is possible.
    assertThatNullPointerException().isThrownBy(() -> h.record(null, 5, 0));
  }

  @Test
  void aFailureOnAProviderThatIsNotRecoveringLeavesRecoveringAtZero() {
    Health h = Health.create(DEFAULTS.withFailureThreshold(99)).record(Outcome.ERROR, 5, 0);
    assertThat(h.recovering()).isZero();
  }

  @Test
  void outcomesCountAsFailuresOnlyWhenErrorOrTimeout() {
    assertThat(Outcome.ERROR.failure()).isTrue();
    assertThat(Outcome.TIMEOUT.failure()).isTrue();
    assertThat(Outcome.SUCCESS.failure()).isFalse();
    assertThat(Outcome.REJECTED.failure()).isFalse();
  }

  @Test
  void statsOfAnEmptyWindow() {
    assertThat(Health.create(DEFAULTS).stats()).isEqualTo(new Stats(0, 0.0, 0.0, 0.0));
  }

  @Test
  void rollingWindowKeepsOnlyTheNewestSamples() {
    Health h = Health.create(DEFAULTS.withWindowSize(3));
    for (int i = 1; i <= 5; i++) {
      h = h.record(Outcome.SUCCESS, i * 10L, i);
    }
    Stats stats = h.stats();
    assertThat(stats.samples()).isEqualTo(3);
    assertThat(stats.meanMs()).isEqualTo(40.0);
    assertThat(stats.p95Ms()).isEqualTo(50.0);
    assertThat(stats.errorRate()).isEqualTo(0.0);
  }

  @Test
  void timeoutsCountAsFailuresWithTheirWaitedLatency() {
    Health h = Health.create(DEFAULTS.withFailureThreshold(99)).record(Outcome.TIMEOUT, 100, 0);
    Stats stats = h.stats();
    assertThat(stats.errorRate()).isEqualTo(1.0);
    assertThat(stats.p95Ms()).isEqualTo(100.0);
  }

  @Test
  void opensAfterConsecutiveFailuresAndSuccessResetsTheStreak() {
    Health h = Health.create(DEFAULTS.withFailureThreshold(3));
    h = fail(h, 2, 0).record(Outcome.SUCCESS, 1, 0);
    h = fail(h, 2, 0);
    assertThat(h.breaker()).isEqualTo(Breaker.CLOSED);

    h = fail(h, 1, 50);
    assertThat(h.breaker()).isEqualTo(Breaker.OPEN);
    assertThat(h.openedAt()).isEqualTo(50);
  }

  @Test
  void deniesWhileOpenAndAdmitsOneProbeAfterCoolDown() {
    Health h = fail(Health.create(DEFAULTS.withFailureThreshold(1).withCoolDownMs(100)), 1, 0);

    assertThat(h.checkout(99)).isEqualTo(new Checkout.Refused(Refusal.CIRCUIT_OPEN));
    Health probing = admittedHealth(h.checkout(100));
    assertThat(probing.breaker()).isEqualTo(Breaker.HALF_OPEN);
    assertThat(probing.probing()).isTrue();
    assertThat(probing.checkout(101)).isEqualTo(new Checkout.Refused(Refusal.CIRCUIT_OPEN));
  }

  @Test
  void successfulProbeClosesTheBreaker() {
    Health h = fail(Health.create(DEFAULTS.withFailureThreshold(1).withCoolDownMs(0)), 1, 0);
    Health probing = admittedHealth(h.checkout(0));
    Health closed = probing.record(Outcome.SUCCESS, 5, 1);

    assertThat(closed.breaker()).isEqualTo(Breaker.CLOSED);
    assertThat(closed.consecutiveFailures()).isZero();
    assertThat(closed.window()).containsExactly(new Health.Sample(Outcome.SUCCESS, 5));
    assertThat(closed.state()).isEqualTo(HealthState.DEGRADED);
    assertThat(closed.checkout(2)).isInstanceOf(Checkout.Admitted.class);
  }

  // Regression: recovery is gradual; a streak of successes is required before the provider is
  // healthy.
  @Test
  void recoveryIsGradualAStreakOfSuccessesIsRequiredToBeHealthy() {
    Health h =
        fail(
            Health.create(
                DEFAULTS.withFailureThreshold(5).withCoolDownMs(0).withRecoverySuccesses(3)),
            5,
            0);
    Health probing = admittedHealth(h.checkout(0));
    h = probing.record(Outcome.SUCCESS, 5, 1);
    assertThat(h.recovering()).isEqualTo(2);
    assertThat(h.state()).isEqualTo(HealthState.DEGRADED);

    h = h.record(Outcome.SUCCESS, 5, 2);
    assertThat(h.state()).isEqualTo(HealthState.DEGRADED);
    h = h.record(Outcome.REJECTED, 5, 3);
    assertThat(h.recovering()).isZero();
    assertThat(h.state()).isEqualTo(HealthState.HEALTHY);
  }

  // Regression: a failure while recovering restarts the success streak.
  @Test
  void aFailureWhileRecoveringRestartsTheStreak() {
    Health h =
        fail(
            Health.create(
                DEFAULTS.withFailureThreshold(5).withCoolDownMs(0).withRecoverySuccesses(3)),
            5,
            0);
    Health probing = admittedHealth(h.checkout(0));
    h = probing.record(Outcome.SUCCESS, 5, 1).record(Outcome.SUCCESS, 5, 2);
    assertThat(h.recovering()).isEqualTo(1);

    h = h.record(Outcome.ERROR, 5, 3);
    assertThat(h.recovering()).isEqualTo(3);
    assertThat(h.breaker()).isEqualTo(Breaker.CLOSED);
  }

  @Test
  void aFailedProbeReopensTheBreakerWithAFreshTimestamp() {
    Health h = fail(Health.create(DEFAULTS.withFailureThreshold(1).withCoolDownMs(10)), 1, 0);
    Health probing = admittedHealth(h.checkout(10));
    Health reopened = probing.record(Outcome.TIMEOUT, 5, 20);

    assertThat(reopened.breaker()).isEqualTo(Breaker.OPEN);
    assertThat(reopened.openedAt()).isEqualTo(20);
  }

  // Regression: late results that arrive while the breaker is open must not change it.
  @Test
  void lateResultsArrivingWhileOpenDoNotChangeTheBreaker() {
    Health h = fail(Health.create(DEFAULTS.withFailureThreshold(1)), 1, 0);
    assertThat(h.record(Outcome.SUCCESS, 1, 5).breaker()).isEqualTo(Breaker.OPEN);
    assertThat(h.record(Outcome.ERROR, 1, 5).breaker()).isEqualTo(Breaker.OPEN);
  }

  // Regression: an abandoned probe frees the probe slot for the next caller.
  @Test
  void anAbandonedProbeFreesTheProbeSlot() {
    Health h = fail(Health.create(DEFAULTS.withFailureThreshold(1).withCoolDownMs(0)), 1, 0);
    Health probing = admittedHealth(h.checkout(0));
    assertThat(probing.checkout(1)).isEqualTo(new Checkout.Refused(Refusal.CIRCUIT_OPEN));

    assertThat(probing.abandon().checkout(2)).isInstanceOf(Checkout.Admitted.class);
  }

  @Test
  void firstSeenUnchangedThenDriftQuarantinesUntilReleased() {
    VersionObservation first = Health.create(DEFAULTS).observeVersion("1");
    assertThat(first.verdict()).isEqualTo(new Verdict.FirstSeen());

    VersionObservation same = first.health().observeVersion("1");
    assertThat(same.verdict()).isEqualTo(new Verdict.Unchanged());

    VersionObservation changed = same.health().observeVersion("2");
    assertThat(changed.verdict()).isEqualTo(new Verdict.Changed("1", "2"));

    Health h = changed.health();
    assertThat(h.drift()).isEqualTo(new Drift("1", "2"));
    assertThat(h.version()).isEqualTo("2");
    assertThat(h.quarantined()).isTrue();
    assertThat(h.checkout(0)).isEqualTo(new Checkout.Refused(Refusal.QUARANTINED));

    Health released = h.release();
    assertThat(released.drift()).isNull();
    assertThat(released.quarantined()).isFalse();
    assertThat(released.checkout(0)).isInstanceOf(Checkout.Admitted.class);
  }
}

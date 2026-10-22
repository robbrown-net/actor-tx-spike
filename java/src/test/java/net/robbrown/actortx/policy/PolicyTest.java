package net.robbrown.actortx.policy;

import static net.robbrown.actortx.policy.Results.errorsOf;
import static net.robbrown.actortx.policy.Results.valid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.robbrown.actortx.provider.HealthState;
import net.robbrown.actortx.provider.Quote;
import net.robbrown.actortx.selection.Candidate;
import org.junit.jupiter.api.Test;

/**
 * Port of the Elixir {@code PolicyTest}: validation, hard constraints, the bps limit and scoring.
 */
final class PolicyTest {

  private static final Quote QUOTE = new Quote("p", "r", 0L, 0L, 0L);
  private static final HealthState HEALTHY = HealthState.HEALTHY;
  private static final String STATE_ERROR =
      "exclude_states: unknown state (expected one of"
          + " [:unknown, :healthy, :degraded, :stalled, :quarantined])";
  private static final String MAX_LATENCY_ERROR =
      "max_latency_ms: must be nil or a positive integer";

  private static Policy policy(Map<String, Object> data) {
    return valid(Policy.fromMap(data));
  }

  private static List<String> errorsFor(Map<String, Object> data) {
    return errorsOf(Policy.fromMap(data));
  }

  private static Candidate cand(long net, long latencyMs, long validityMs, HealthState state) {
    return new Candidate("p", QUOTE, net, latencyMs, validityMs, state, 0.0);
  }

  private static Candidate cand(long net, long latencyMs, HealthState state) {
    return cand(net, latencyMs, 10_000, state);
  }

  @Test
  void emptyDataYieldsDefaults() {
    Policy p = policy(Map.of());
    assertThat(p).isEqualTo(Policy.defaults());
    assertThat(p.minValidityMs()).isZero();
    assertThat(p.excludeStates())
        .containsExactlyInAnyOrder(HealthState.STALLED, HealthState.QUARANTINED);
  }

  @Test
  void acceptsStringKeysAndNormalisesWeights() {
    Policy p =
        policy(
            Map.of(
                "weights",
                Map.of("cost", 3, "speed", 1),
                "min_validity_ms",
                5_000,
                "max_latency_ms",
                2_000,
                "exclude_states",
                List.of("stalled", "degraded")));

    assertThat(p.weights()).isEqualTo(new Weights(0.75, 0.25, 0.0));
    assertThat(p.minValidityMs()).isEqualTo(5_000);
    assertThat(p.maxLatencyMs()).isEqualTo(2_000L);
    assertThat(p.excludeStates())
        .containsExactlyInAnyOrder(HealthState.STALLED, HealthState.DEGRADED);
  }

  @Test
  void acceptsExplicitNullMaxLatencyAndNormalisesASingleWeight() {
    Map<String, Object> raw = new HashMap<>();
    raw.put("weights", Map.of("reliability", 2));
    raw.put("max_latency_ms", null);

    Policy p = policy(raw);

    assertThat(p.maxLatencyMs()).isNull();
    assertThat(p.weights()).isEqualTo(new Weights(0.0, 0.0, 1.0));
  }

  @Test
  void collectsEveryValidationError() {
    Map<String, Object> raw =
        Map.of(
            "bogus", 1,
            "weights", Map.of("cost", -1),
            "min_validity_ms", -5,
            "max_latency_ms", 0,
            "exclude_states", List.of("sleepy"));

    assertThat(errorsFor(raw))
        .containsExactlyInAnyOrder(
            "unknown key: bogus",
            "weights: must be non-negative numbers",
            "min_validity_ms: must be a non-negative integer",
            MAX_LATENCY_ERROR,
            STATE_ERROR);
  }

  @Test
  void weightValidationVariants() {
    assertThat(errorsFor(Map.of("weights", Map.of("cost", 0))))
        .containsExactly("weights: must not all be zero");
    assertThat(errorsFor(Map.of("weights", Map.of("luck", 1))))
        .containsExactly("weights: unknown weights: luck");
    assertThat(errorsFor(Map.of("weights", "heavy"))).containsExactly("weights: must be a map");
    assertThat(errorsFor(Map.of("weights", Map.of("cost", "x"))))
        .containsExactly("weights: must be non-negative numbers");
  }

  @Test
  void zeroMinimumValidityIsValidAndErrorMessagesAreExact() {
    assertThat(policy(Map.of("min_validity_ms", 0)).minValidityMs()).isZero();
    assertThat(errorsFor(Map.of("max_latency_ms", 0))).containsExactly(MAX_LATENCY_ERROR);
    assertThat(errorsFor(Map.of("max_bps_from_best", -5)))
        .containsExactly("max_bps_from_best: must be nil or a non-negative number");
  }

  @Test
  void otherFieldTypeErrors() {
    assertThat(errorsFor(Map.of("exclude_states", "stalled")))
        .containsExactly("exclude_states: must be a list");
    assertThat(errorsFor(Map.of("max_latency_ms", "fast"))).containsExactly(MAX_LATENCY_ERROR);
  }

  @Test
  void downIsNotAConfigurableState() {
    assertThat(errorsFor(Map.of("exclude_states", List.of("down")))).containsExactly(STATE_ERROR);
  }

  @Test
  void maxBpsFromBestValidation() {
    assertThat(policy(Map.of("max_bps_from_best", 25)).maxBpsFromBest()).isEqualTo(25.0);
    Map<String, Object> nullLimit = new HashMap<>();
    nullLimit.put("max_bps_from_best", null);
    assertThat(policy(nullLimit).maxBpsFromBest()).isNull();
    assertThat(errorsFor(Map.of("max_bps_from_best", -1)))
        .containsExactly("max_bps_from_best: must be nil or a non-negative number");
  }

  @Test
  void noLimitKeepsEveryoneAndEmptySetIsFine() {
    List<Candidate> cands = List.of(cand(100, 0, HEALTHY), cand(50, 0, HEALTHY));

    Policy.WithinBest all = Policy.defaults().withinBest(cands);

    assertThat(all.kept()).isEqualTo(cands);
    assertThat(all.dropped()).isEmpty();
    assertThat(policy(Map.of("max_bps_from_best", 10)).withinBest(List.of()).kept()).isEmpty();
  }

  @Test
  void dropsCandidatesFurtherThanTheLimitFromTheBestAndReportsBps() {
    Policy p = policy(Map.of("max_bps_from_best", 50));
    List<Candidate> cands =
        List.of(cand(1000, 0, HEALTHY), cand(996, 0, HEALTHY), cand(990, 0, HEALTHY));

    Policy.WithinBest result = p.withinBest(cands);

    assertThat(result.kept()).extracting(Candidate::net).containsExactly(1000L, 996L);
    assertThat(result.dropped()).hasSize(1);
    Policy.TooDear dropped = result.dropped().get(0);
    assertThat(dropped.candidate().net()).isEqualTo(990L);
    assertThat(dropped.bps()).isCloseTo(100.0, within(0.001));
  }

  @Test
  void aCandidateExactlyAtTheLimitIsKeptBecauseTheBoundaryIsInclusive() {
    Policy p = policy(Map.of("max_bps_from_best", 100));
    List<Candidate> cands = List.of(cand(1000, 0, HEALTHY), cand(990, 0, HEALTHY));

    Policy.WithinBest result = p.withinBest(cands);

    assertThat(result.kept()).isEqualTo(cands);
    assertThat(result.dropped()).isEmpty();
  }

  @Test
  void zeroIsAValidLimitAndOnlyTheBestSurvives() {
    assertThat(policy(Map.of("max_bps_from_best", 0.0)).maxBpsFromBest()).isEqualTo(0.0);
    assertThat(policy(Map.of("max_bps_from_best", 0)).maxBpsFromBest()).isEqualTo(0.0);

    Policy.WithinBest result =
        policy(Map.of("max_bps_from_best", 0))
            .withinBest(List.of(cand(10, 0, HEALTHY), cand(9, 0, HEALTHY)));

    assertThat(result.kept()).extracting(Candidate::net).containsExactly(10L);
    assertThat(result.dropped()).extracting(d -> d.candidate().net()).containsExactly(9L);
  }

  @Test
  void aNonPositiveBestDoesNotDivideByZero() {
    Policy p = policy(Map.of("max_bps_from_best", 1));
    List<Candidate> cands = List.of(cand(0, 0, HEALTHY), cand(-1, 0, HEALTHY));

    Policy.WithinBest result = p.withinBest(cands);

    assertThat(result.kept()).isEqualTo(cands);
    assertThat(result.dropped()).isEmpty();
  }

  @Test
  void excludesStateAlwaysExcludesDown() {
    Policy p = policy(Map.of("exclude_states", List.of()));
    assertThat(p.excludesState(HealthState.DOWN)).isTrue();
    assertThat(p.excludesState(HealthState.STALLED)).isFalse();
    assertThat(Policy.defaults().excludesState(HealthState.STALLED)).isTrue();
  }

  @Test
  void checkOkWithinConstraints() {
    Policy p = policy(Map.of("min_validity_ms", 100, "max_latency_ms", 50));
    assertThat(p.check(cand(0, 50, 100, HEALTHY))).isEmpty();
  }

  @Test
  void validityAndLatencyViolations() {
    Policy p = policy(Map.of("min_validity_ms", 100, "max_latency_ms", 50));
    assertThat(p.check(cand(0, 1, 99, HEALTHY))).contains(Policy.Exclusion.QUOTE_EXPIRES_TOO_SOON);
    assertThat(p.check(cand(0, 51, 500, HEALTHY))).contains(Policy.Exclusion.TOO_SLOW);
  }

  @Test
  void noLatencyLimitByDefault() {
    assertThat(Policy.defaults().check(cand(0, 1_000_000, 0, HEALTHY))).isEmpty();
  }

  @Test
  void scoresOfEmptyCandidatesAreEmpty() {
    assertThat(Policy.defaults().scores(List.of())).isEmpty();
  }

  @Test
  void costOnlyWeightsRankByNet() {
    Policy p = policy(Map.of("weights", Map.of("cost", 1)));

    assertThat(
            p.scores(List.of(cand(110, 5, HEALTHY), cand(100, 1, HEALTHY), cand(105, 9, HEALTHY))))
        .containsExactly(1.0, 0.0, 0.5);
  }

  @Test
  void speedOnlyWeightsRankByLatencyLowerIsBetter() {
    Policy p = policy(Map.of("weights", Map.of("speed", 1)));

    assertThat(p.scores(List.of(cand(1, 100, HEALTHY), cand(1, 0, HEALTHY))))
        .containsExactly(0.0, 1.0);
  }

  @Test
  void reliabilityUsesAFixedFactorPerStateAndUnknownStatesScoreZero() {
    Policy p = policy(Map.of("weights", Map.of("reliability", 1)));

    assertThat(
            p.scores(
                List.of(
                    cand(1, 1, HealthState.HEALTHY),
                    cand(1, 1, HealthState.UNKNOWN),
                    cand(1, 1, HealthState.DEGRADED),
                    cand(1, 1, HealthState.STALLED))))
        .containsExactly(1.0, 0.7, 0.4, 0.0);
  }

  @Test
  void equalCandidatesAllScoreTheFullCostAndSpeedShare() {
    Policy p = policy(Map.of("weights", Map.of("cost", 1, "speed", 1)));

    assertThat(p.scores(List.of(cand(5, 5, HEALTHY), cand(5, 5, HEALTHY))))
        .containsExactly(1.0, 1.0);
  }
}

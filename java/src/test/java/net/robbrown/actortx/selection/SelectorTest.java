package net.robbrown.actortx.selection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import net.robbrown.actortx.money.Money;
import net.robbrown.actortx.policy.Policy;
import net.robbrown.actortx.provider.Answer;
import net.robbrown.actortx.provider.HealthState;
import net.robbrown.actortx.provider.Quote;
import net.robbrown.actortx.selection.Evaluation.Item;
import org.junit.jupiter.api.Test;

/** The pure judgement: observations in, ranking out. No processes, no clock. */
class SelectorTest {

  private static final long AMOUNT = 100_000;
  private static final long SEEN_AT = 1_000;

  private static Observation quote(
      String id, String rate, String fee, long latencyMs, HealthState state) {
    Quote quote = new Quote(id, "r", Money.rate(rate), Money.minor(fee), SEEN_AT + 60_000);
    return new Observation(id, state, new Answer.Ok<>(quote), latencyMs, SEEN_AT);
  }

  private static Observation quote(String id, String rate) {
    return quote(id, rate, "0", 10, HealthState.HEALTHY);
  }

  private static Observation failed(String id, Answer<Quote> answer) {
    return new Observation(id, HealthState.HEALTHY, answer, 5, SEEN_AT);
  }

  private static List<String> ranking(Evaluation evaluation) {
    return evaluation.ranked().stream().map(Candidate::providerId).toList();
  }

  @Test
  void aCostOnlyPolicySelectsTheHighestNetValue() {
    Evaluation evaluation =
        Selector.evaluate(
            AMOUNT,
            List.of(quote("cheap", "1.00"), quote("best", "1.10"), quote("mid", "1.05")),
            Policies.costOnly());

    assertThat(ranking(evaluation)).containsExactly("best", "mid", "cheap");
    assertThat(evaluation.ranked().get(0).net()).isEqualTo(110_000L);
    assertThat(evaluation.ranked().get(0).score()).isEqualTo(1.0);
    assertThat(evaluation.ranked().get(0).validityMs()).isEqualTo(60_000L);
    assertThat(evaluation.excluded()).isEmpty();
  }

  @Test
  void feesArePartOfCost() {
    Evaluation evaluation =
        Selector.evaluate(
            AMOUNT,
            List.of(
                quote("high-rate-high-fee", "1.10", "100", 10, HealthState.HEALTHY),
                quote("lower-rate-no-fee", "1.05", "0", 10, HealthState.HEALTHY)),
            Policies.costOnly());

    assertThat(ranking(evaluation)).containsExactly("lower-rate-no-fee", "high-rate-high-fee");
  }

  @Test
  void weightsExpressTheCostSpeedTradeOff() {
    List<Observation> market =
        List.of(
            quote("cheap-slow", "1.10", "0", 900, HealthState.HEALTHY),
            quote("dear-fast", "1.08", "0", 10, HealthState.HEALTHY));

    assertThat(ranking(Selector.evaluate(AMOUNT, market, Policies.weights(0.95, 0.05, 0))))
        .containsExactly("cheap-slow", "dear-fast");
    assertThat(ranking(Selector.evaluate(AMOUNT, market, Policies.weights(0.2, 0.8, 0))))
        .containsExactly("dear-fast", "cheap-slow");
  }

  @Test
  void reliabilityPrefersAProvenProviderOverAnUnknownOne() {
    List<Observation> market =
        List.of(
            quote("unknown", "1.05", "0", 10, HealthState.UNKNOWN),
            quote("healthy", "1.05", "0", 10, HealthState.HEALTHY),
            quote("degraded", "1.05", "0", 10, HealthState.DEGRADED));

    assertThat(ranking(Selector.evaluate(AMOUNT, market, Policies.weights(0.1, 0, 0.9))))
        .containsExactly("healthy", "unknown", "degraded");
  }

  @Test
  void maxBpsFromBestStopsSpeedFromBuyingAnUnacceptablyBadPrice() {
    Policy capped =
        Policies.of(Map.of("weights", Map.of("cost", 0.1, "speed", 0.9), "max_bps_from_best", 100));
    List<Observation> market =
        List.of(
            quote("best-price", "1.10", "0", 900, HealthState.HEALTHY),
            quote("fast-but-dear", "1.00", "0", 1, HealthState.HEALTHY));

    Evaluation evaluation = Selector.evaluate(AMOUNT, market, capped);

    assertThat(ranking(evaluation)).containsExactly("best-price");
    assertThat(evaluation.excluded())
        .containsExactly(new Excluded("fast-but-dear", "worse_than_best_bps: 909.1"));
  }

  @Test
  void tiesAreBrokenDeterministicallyByProviderId() {
    Evaluation evaluation =
        Selector.evaluate(
            AMOUNT,
            List.of(quote("b", "1.05"), quote("a", "1.05"), quote("c", "1.05")),
            Policies.costOnly());

    assertThat(ranking(evaluation)).containsExactly("a", "b", "c");
  }

  @Test
  void hardConstraintsExcludeCandidatesWithAReason() {
    Policy strict = Policies.of(Map.of("min_validity_ms", 70_000, "max_latency_ms", 100));
    Quote shortLived = new Quote("short", "r", Money.rate("1.1"), 0, SEEN_AT + 1_000);
    List<Observation> market =
        List.of(
            new Observation("short", HealthState.HEALTHY, new Answer.Ok<>(shortLived), 10, SEEN_AT),
            quote("slow", "1.10", "0", 500, HealthState.HEALTHY));

    Evaluation evaluation = Selector.evaluate(AMOUNT, market, strict);

    assertThat(evaluation.ranked()).isEmpty();
    assertThat(evaluation.excluded())
        .containsExactly(
            new Excluded("short", "quote_expires_too_soon"),
            new Excluded("slow", "quote_expires_too_soon"));
  }

  @Test
  void aSlowProviderWithAValidQuoteIsExcludedAsTooSlow() {
    Policy fastOnly = Policies.of(Map.of("max_latency_ms", 100));

    Evaluation evaluation =
        Selector.evaluate(
            AMOUNT, List.of(quote("slow", "1.10", "0", 500, HealthState.HEALTHY)), fastOnly);

    assertThat(evaluation.excluded()).containsExactly(new Excluded("slow", "too_slow"));
    assertThat(evaluation.items().get(0)).isInstanceOf(Item.Refused.class);
  }

  @Test
  void rejectionsErrorsAndTimeoutsAreReportedAsFailures() {
    Evaluation evaluation =
        Selector.evaluate(
            AMOUNT,
            List.of(
                quote("good", "1.05"),
                failed("no", new Answer.Rejected<>("no_liquidity")),
                failed("boom", new Answer.Error<>("down")),
                failed("late", new Answer.Timeout<>())),
            Policies.costOnly());

    assertThat(ranking(evaluation)).containsExactly("good");
    assertThat(evaluation.excluded())
        .containsExactly(
            new Excluded("no", "rejected: no_liquidity"),
            new Excluded("boom", "error: down"),
            new Excluded("late", "timeout"));
    assertThat(evaluation.items()).hasSize(4);
    assertThat(evaluation.items().get(0)).isInstanceOf(Item.Accepted.class);
    assertThat(evaluation.items().get(3)).isInstanceOf(Item.Failed.class);
  }

  @Test
  void anyOtherAnswerCountsAsNoQuote() {
    Evaluation evaluation =
        Selector.evaluate(
            AMOUNT, List.of(failed("odd", new Answer.InDoubt<>())), Policies.costOnly());

    assertThat(evaluation.excluded()).containsExactly(new Excluded("odd", "timeout"));
  }

  @Test
  void nothingObservedGivesAnEmptyEvaluation() {
    Evaluation evaluation = Selector.evaluate(AMOUNT, List.of(), Policies.costOnly());

    assertThat(evaluation.items()).isEmpty();
    assertThat(evaluation.ranked()).isEmpty();
    assertThat(evaluation.excluded()).isEmpty();
  }

  @Test
  void theDecisionIsTheBestCandidateOrTheBaselineOrNothing() {
    Candidate best =
        new Candidate("a", new Quote("a", "r", 1, 0, 1), 1, 1, 1, HealthState.HEALTHY, 1);

    assertThat(Selection.decide(List.of(best), "base"))
        .isEqualTo(new Selection.Outcome.Selected(best));
    assertThat(Selection.decide(List.of(), "base"))
        .isEqualTo(new Selection.Outcome.Fallback("base"));
    assertThat(Selection.decide(List.of(), null)).isEqualTo(new Selection.Outcome.None());
  }

  @Test
  void aParticipantCarriesAPairOnlyIfItListsBothCurrencies() {
    Participant open = new Participant("open", null, null);
    Participant narrow = new Participant("narrow", null, java.util.Set.of("USD", "EUR"));

    assertThat(open.carries("USD", "INR")).isTrue();
    assertThat(narrow.carries("USD", "EUR")).isTrue();
    assertThat(narrow.carries("USD", "INR")).isFalse();
    assertThat(narrow.carries("INR", "USD")).isFalse();
    assertThat(narrow.carries(null, "INR")).isTrue();
    assertThat(narrow.carries("USD", null)).isTrue();
  }
}

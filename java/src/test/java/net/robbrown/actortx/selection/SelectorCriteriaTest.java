package net.robbrown.actortx.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import net.robbrown.actortx.money.Money;
import net.robbrown.actortx.policy.Policy;
import net.robbrown.actortx.policy.criteria.CostCriterion;
import net.robbrown.actortx.policy.criteria.Criteria;
import net.robbrown.actortx.policy.criteria.ReliabilityCriterion;
import net.robbrown.actortx.policy.criteria.SpeedCriterion;
import net.robbrown.actortx.provider.Answer;
import net.robbrown.actortx.provider.HealthState;
import net.robbrown.actortx.provider.Quote;
import net.robbrown.actortx.validation.Validated;
import org.junit.jupiter.api.Test;

/** A criterion added to the registry, by one class, changes the ranking; no other code does. */
class SelectorCriteriaTest {

  private static final long AMOUNT = 100_000;
  private static final long SEEN_AT = 1_000;

  // The one line a new criterion needs: it is listed in the registry.
  private static final Criteria WITH_NO_FEE =
      Criteria.of(
          List.of(
              new CostCriterion(),
              new SpeedCriterion(),
              new ReliabilityCriterion(),
              new NoFeeCriterion()));

  // Best net (109_000) but charges a fee; slightly worse net (108_000) and no fee.
  private static final List<Observation> MARKET =
      List.of(quote("high-fee", "1.10", "10"), quote("no-fee", "1.08", "0"));

  private static Observation quote(String id, String rate, String fee) {
    Quote quote = new Quote(id, "r", Money.rate(rate), Money.minor(fee), SEEN_AT + 60_000);
    return new Observation(id, HealthState.HEALTHY, new Answer.Ok<>(quote), 10, SEEN_AT);
  }

  private static Policy policy(Criteria criteria, Map<String, Object> data) {
    return switch (Policy.fromMap(data, criteria)) {
      case Validated.Valid<Policy> valid -> valid.value();
      case Validated.Invalid<Policy> invalid ->
          throw new IllegalArgumentException(invalid.errors().toString());
    };
  }

  private static List<String> ranking(Evaluation evaluation) {
    return evaluation.ranked().stream().map(Candidate::providerId).toList();
  }

  @Test
  void theStandardRegistryDoesNotKnowTheNewCriterion() {
    assertThatThrownBy(() -> policy(Criteria.standard(), Map.of("weights", Map.of("no_fee", 1))))
        .hasMessageContaining("unknown weights: no_fee");
  }

  @Test
  void aNewCriterionIsAcceptedByTheValidatorAndChangesTheRanking() {
    Policy costOnly = policy(WITH_NO_FEE, Map.of("weights", Map.of("cost", 1)));
    Policy noFeeOnly = policy(WITH_NO_FEE, Map.of("weights", Map.of("no_fee", 1)));

    assertThat(ranking(Selector.evaluate(AMOUNT, MARKET, costOnly, WITH_NO_FEE)))
        .containsExactly("high-fee", "no-fee");
    assertThat(ranking(Selector.evaluate(AMOUNT, MARKET, noFeeOnly, WITH_NO_FEE)))
        .containsExactly("no-fee", "high-fee");
  }

  @Test
  void aCriterionTheEvaluationOmitsGetsWeightZero() {
    Policy costOnly = policy(WITH_NO_FEE, Map.of("weights", Map.of("cost", 1)));

    Evaluation withNew = Selector.evaluate(AMOUNT, MARKET, costOnly, WITH_NO_FEE);
    Evaluation standard = Selector.evaluate(AMOUNT, MARKET, costOnly, Criteria.standard());

    assertThat(withNew.ranked().stream().map(Candidate::score))
        .containsExactlyElementsOf(standard.ranked().stream().map(Candidate::score).toList());
  }

  @Test
  void theScoreIsTheWeightedSumOverTheRegistry() {
    Policy mixed = policy(WITH_NO_FEE, Map.of("weights", Map.of("cost", 3, "no_fee", 1)));

    Evaluation evaluation = Selector.evaluate(AMOUNT, MARKET, mixed, WITH_NO_FEE);

    // high-fee: cost 1.0 * 0.75 + no_fee 0 * 0.25; no-fee: cost 0.0 * 0.75 + no_fee 1 * 0.25.
    assertThat(evaluation.ranked().get(0).providerId()).isEqualTo("high-fee");
    assertThat(evaluation.ranked().get(0).score()).isEqualTo(0.75);
    assertThat(evaluation.ranked().get(1).score()).isEqualTo(0.25);
  }
}

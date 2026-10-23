package net.robbrown.actortx.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import net.robbrown.actortx.validation.Validated;
import org.junit.jupiter.api.Test;

class PlanTest {

  /** Parameters from alternating key and value arguments; null values are allowed. */
  static Map<String, Object> params(Object... keysAndValues) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      map.put((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return map;
  }

  static Plan valid(Map<String, Object> params) {
    return switch (Plan.of(params)) {
      case Validated.Valid<Plan> valid -> valid.value();
      case Validated.Invalid<Plan> invalid -> throw new AssertionError(invalid.errors());
    };
  }

  static List<String> errors(Map<String, Object> params) {
    return switch (Plan.of(params)) {
      case Validated.Valid<Plan> valid -> throw new AssertionError("valid: " + valid.value());
      case Validated.Invalid<Plan> invalid -> invalid.errors();
    };
  }

  @Test
  void aPlanNeedsOnlyWhatYouWantToChange() {
    Plan plan = valid(params());

    assertThat(plan.count()).isEqualTo(10);
    assertThat(plan.mode()).isEqualTo(Plan.Mode.FIXED);
    assertThat(plan.intervalMs()).isEqualTo(100);
    assertThat(plan.seed()).isNull();
    assertThat(plan.fixed()).isEmpty();
    assertThat(plan.randomise()).isEqualTo(CommandGenerator.SETTINGS);
    assertThat(plan.minMinor()).isLessThanOrEqualTo(plan.maxMinor());
  }

  @Test
  void everyProblemIsReportedAtOnce() {
    List<String> errors =
        errors(
            params(
                "count",
                0,
                "min_amount",
                "abc",
                "max_amount",
                -5,
                "interval_ms",
                70_000,
                "mode",
                "chaos",
                "randomise",
                List.of("merchant", "colour"),
                "fixed",
                Map.of("flavour", "x"),
                "seed",
                "seven"));

    String text = String.join("\n", errors);
    for (String part : FIELDS_WITH_PROBLEMS) {
      assertThat(text).as(part).contains(part);
    }
    assertThat(text).contains("colour").contains("flavour");
  }

  private static final List<String> FIELDS_WITH_PROBLEMS =
      List.of(
          "count", "min_amount", "max_amount", "interval_ms", "mode", "randomise", "fixed", "seed");

  @Test
  void countAndIntervalHaveLimitsAndAmountsMayNotBeReversed() {
    assertThat(errors(params("count", 10_001))).singleElement().asString().contains("1 to 10000");
    assertThat(valid(params("count", 10_000, "interval_ms", 0)).count()).isEqualTo(10_000);
    assertThat(valid(params("mode", "fixed", "seed", null)).seed()).isNull();
    assertThat(errors(params("min_amount", 50, "max_amount", 10)))
        .containsExactly("max_amount: must not be below min_amount");
    assertThat(errors(params("min_amount", null))).isNotEmpty();
    assertThat(errors(params("min_amount", 0)))
        .containsExactly("min_amount: must be a positive amount");
    assertThat(errors(params("mode", "chaos")))
        .containsExactly("mode: must be \"fixed\" or \"random\"");
    assertThat(errors(params("seed", "x")))
        .containsExactly("seed: must be a whole number or empty");
    assertThat(errors(params("randomise", "all"))).isNotEmpty();
    assertThat(errors(params("fixed", "acme"))).isNotEmpty();
  }

  @Test
  void amountsAreReadAsMajorUnitsIntoMinorUnits() {
    Plan plan = valid(params("min_amount", "1.50", "max_amount", 9));

    assertThat(plan.minMinor()).isEqualTo(150);
    assertThat(plan.maxMinor()).isEqualTo(900);
  }

  @Test
  void aPlanDescribesItself() {
    String text = valid(params("mode", "random", "count", 7, "seed", 2)).describe();

    assertThat(text).contains("7 ×").contains("random").contains("seed 2");
    assertThat(valid(params()).describe()).doesNotContain("seed");
  }

  @Test
  void theMaximumInFlightIsValidatedAndDescribed() {
    Plan plan = valid(params("max_in_flight", 50));

    assertThat(plan.maxInFlight()).isEqualTo(50);
    assertThat(plan.describe()).contains("at most 50 in flight");
    assertThat(valid(params()).describe()).doesNotContain("in flight");
    assertThat(errors(params("max_in_flight", -1)))
        .containsExactly("max_in_flight: must be a whole number from 0 (no limit) to 10000");
    assertThat(errors(params("max_in_flight", 10_001))).isNotEmpty();
  }

  @Test
  void aPlanIsRecordedAsPlainDataInMinorUnits() {
    Plan plan =
        valid(
            params(
                "count", 3,
                "min_amount", "1.50",
                "max_amount", 9,
                "mode", "random",
                "seed", 4,
                "max_in_flight", 2,
                "fixed", Map.of("region", "UK")));

    assertThat(plan.toMap())
        .containsEntry("count", 3)
        .containsEntry("min_minor", 150L)
        .containsEntry("max_minor", 900L)
        .containsEntry("mode", "random")
        .containsEntry("seed", 4L)
        .containsEntry("max_in_flight", 2)
        .containsEntry("fixed", Map.of("region", "UK"))
        .containsEntry("interval_ms", 100L)
        .containsEntry("randomise", CommandGenerator.SETTINGS);
  }

  @Test
  void blankAndMissingFixedSettingsAreLeftOut() {
    Plan plan =
        valid(
            params(
                "fixed",
                params("merchant", "acme", "customer", "", "origin", null, "region", "UK")));

    assertThat(plan.fixed())
        .containsExactlyInAnyOrderEntriesOf(Map.of("merchant", "acme", "region", "UK"));
  }

  @Test
  void aSeededPlanUsesItsOwnRandomSourceAndAnUnseededOneTheInjectedOne() {
    RandomGenerator injected = new java.util.SplittableRandom(1);

    assertThat(valid(params()).generatorFor(injected)).isSameAs(injected);
    assertThat(valid(params("seed", 9)).generatorFor(injected)).isNotSameAs(injected);
  }
}

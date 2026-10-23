package net.robbrown.actortx.bulk;

import static net.robbrown.actortx.bulk.PlanTest.params;
import static net.robbrown.actortx.bulk.PlanTest.valid;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;
import net.robbrown.actortx.service.PaymentCommand;
import org.junit.jupiter.api.Test;

class CommandGeneratorTest {

  private static final Map<String, Function<PaymentCommand, String>> FIELDS =
      Map.of(
          "merchant", PaymentCommand::merchantId,
          "customer", PaymentCommand::customerId,
          "origin", PaymentCommand::originRegion,
          "region", PaymentCommand::destinationRegion,
          "instrument", PaymentCommand::instrument,
          "customer_type", PaymentCommand::customerType,
          "source_currency", PaymentCommand::sourceCurrency,
          "destination_currency", PaymentCommand::destinationCurrency);

  private static List<PaymentCommand> generate(Plan plan, RandomGenerator random, int n) {
    CommandGenerator generator = new CommandGenerator(plan, plan.generatorFor(random));
    return IntStream.range(0, n).mapToObj(i -> generator.next()).toList();
  }

  private static List<PaymentCommand> seeded(Map<String, Object> params, int n) {
    return generate(valid(params), new SplittableRandom(0), n);
  }

  private static List<String> valuesOf(List<PaymentCommand> commands, String key) {
    return commands.stream().map(command -> FIELDS.get(key).apply(command)).distinct().toList();
  }

  @Test
  void amountsAreDrawnInclusiveOfBothEnds() {
    List<BigDecimal> amounts =
        seeded(params("min_amount", "10.00", "max_amount", "10.05", "seed", 1), 300).stream()
            .map(PaymentCommand::amount)
            .distinct()
            .sorted()
            .toList();

    assertThat(amounts)
        .containsExactly(
            new BigDecimal("10.00"),
            new BigDecimal("10.01"),
            new BigDecimal("10.02"),
            new BigDecimal("10.03"),
            new BigDecimal("10.04"),
            new BigDecimal("10.05"));
  }

  @Test
  void equalMinimumAndMaximumIsAFixedAmount() {
    List<BigDecimal> amounts =
        seeded(params("min_amount", 25, "max_amount", 25), 5).stream()
            .map(PaymentCommand::amount)
            .distinct()
            .toList();

    assertThat(amounts).containsExactly(new BigDecimal("25.00"));
  }

  @Test
  void aSeededPlanRepeatsExactlyAndAnUnseededOneDoesNot() {
    Map<String, Object> seeded = params("mode", "random", "seed", 42, "max_amount", 100_000);
    Map<String, Object> other = params("mode", "random", "seed", 43, "max_amount", 100_000);
    Map<String, Object> unseeded = params("mode", "random", "max_amount", 100_000);

    assertThat(seeded(seeded, 20)).isEqualTo(seeded(seeded, 20));
    assertThat(seeded(other, 20)).isNotEqualTo(seeded(seeded, 20));
    Plan plan = valid(unseeded);
    assertThat(generate(plan, new SplittableRandom(1), 20))
        .isNotEqualTo(generate(plan, new SplittableRandom(2), 20));
  }

  @Test
  void fixedModeVariesOnlyTheAmountAndUsesTheFixedSettingsAsGiven() {
    List<PaymentCommand> commands =
        seeded(
            params(
                "seed",
                5,
                "fixed",
                params("merchant", "acme", "customer", "", "origin", null, "region", "UK")),
            30);

    assertThat(valuesOf(commands, "merchant")).containsExactly("acme");
    assertThat(valuesOf(commands, "region")).containsExactly("UK");
    assertThat(valuesOf(commands, "customer")).containsExactly((String) null);
    assertThat(valuesOf(commands, "origin")).containsExactly((String) null);
    assertThat(valuesOf(commands, "instrument")).containsExactly((String) null);
    assertThat(commands.stream().map(PaymentCommand::amount).distinct().count()).isGreaterThan(1);
  }

  @Test
  void randomModeDrawsEverySettingFromWhatTheDemoKnows() {
    List<PaymentCommand> commands = seeded(params("mode", "random", "seed", 9), 400);

    for (PaymentCommand command : commands) {
      for (String key : CommandGenerator.SETTINGS) {
        assertThat(CommandGenerator.choicesFor(key)).contains(FIELDS.get(key).apply(command));
      }
    }
    for (String key :
        List.of(
            "merchant",
            "region",
            "instrument",
            "customer_type",
            "source_currency",
            "destination_currency")) {
      assertThat(valuesOf(commands, key)).as(key).hasSizeGreaterThan(1);
    }
  }

  @Test
  void aPaymentNeverConvertsACurrencyIntoItself() {
    for (PaymentCommand command : seeded(params("mode", "random", "seed", 3), 500)) {
      assertThat(command.destinationCurrency()).isNotEqualTo(command.sourceCurrency());
    }
  }

  @Test
  void randomModeRandomisesOnlyTheSettingsListed() {
    List<PaymentCommand> commands =
        seeded(
            params(
                "mode",
                "random",
                "randomise",
                List.of("instrument", "customer_type"),
                "fixed",
                params("merchant", "globex", "region", "EU", "instrument", "spot"),
                "seed",
                11),
            200);

    assertThat(valuesOf(commands, "merchant")).containsExactly("globex");
    assertThat(valuesOf(commands, "region")).containsExactly("EU");
    assertThat(valuesOf(commands, "instrument")).containsExactlyInAnyOrder("forward", "spot");
    assertThat(valuesOf(commands, "source_currency")).containsExactly((String) null);
  }

  @Test
  void theSettingsAreSortedAndEachHasItsChoices() {
    assertThat(CommandGenerator.SETTINGS)
        .isSorted()
        .containsExactlyInAnyOrderElementsOf(FIELDS.keySet());
    assertThat(CommandGenerator.choicesFor("instrument")).containsExactly("spot", "forward");
  }
}

package net.robbrown.actortx.eligibility;

import static net.robbrown.actortx.eligibility.EligibilityTest.build;
import static net.robbrown.actortx.eligibility.EligibilityTest.errorsOf;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.robbrown.actortx.validation.Validated.Valid;
import org.junit.jupiter.api.Test;

class RuleParserTest {

  private static final String INSTRUMENT_UNKNOWN =
      "unknown value (expected some of [:spot, :forward])";
  private static final String CUSTOMER_UNKNOWN =
      "unknown value (expected some of [:business, :retail])";
  private static final String PROVIDERS =
      "must be \"all\", {\"allow\": [...]} or {\"deny\": [...]}";
  private static final String CURRENCIES = "must be \"all\" or a non-empty list of currency codes";
  private static final String RETENTION = "must be nil or a positive number of days";

  private static List<String> defaultErrors(String key, Object value) {
    return errorsOf(Map.of("default", Map.of(key, value)));
  }

  private static List<String> regionErrors(String key, Object value) {
    return errorsOf(Map.of("regions", Map.of("IN", Map.of(key, value))));
  }

  @Test
  void rootsThatAreNotMapsAreReported() {
    assertThat(errorsOf(Map.of("default", 1))).containsExactly("default: must be a map");
    assertThat(errorsOf(Map.of("regions", "x"))).containsExactly("regions: must be a map");
    assertThat(errorsOf(Map.of("regions", Map.of("IN", "nope"))))
        .containsExactly("regions.IN: must be a map");
  }

  @Test
  void unknownTopLevelAndRuleKeysAreReportedBeforeFieldErrors() {
    assertThat(errorsOf(Map.of("zeta", 1, "alpha", 2)))
        .containsExactly("unknown key: alpha", "unknown key: zeta");
    assertThat(errorsOf(Map.of("default", Map.of("zeta", 1, "logs", "x"))))
        .containsExactly(
            "default: unknown key zeta", "default.logs: must be a non-empty list of log names");
  }

  @Test
  void instrumentsMustBeANonEmptyListOfKnownNames() {
    assertThat(defaultErrors("instruments", "spot"))
        .containsExactly("default.instruments: must be a non-empty list");
    assertThat(defaultErrors("instruments", List.of()))
        .containsExactly("default.instruments: must be a non-empty list");
    assertThat(defaultErrors("instruments", List.of("swap")))
        .containsExactly("default.instruments: " + INSTRUMENT_UNKNOWN);
    assertThat(defaultErrors("instruments", List.of("spot", "forward"))).isEmpty();
  }

  @Test
  void customerTypesMustBeANonEmptyListOfKnownNames() {
    assertThat(defaultErrors("customer_types", List.of()))
        .containsExactly("default.customer_types: must be a non-empty list");
    assertThat(regionErrors("customer_types", List.of("wholesale")))
        .containsExactly("regions.IN.customer_types: " + CUSTOMER_UNKNOWN);
    assertThat(regionErrors("customer_types", List.of("retail"))).isEmpty();
  }

  @Test
  void currenciesAreAllOrACodeList() {
    assertThat(defaultErrors("currencies", "all")).isEmpty();
    assertThat(defaultErrors("currencies", List.of("INR", "USD"))).isEmpty();
    assertThat(defaultErrors("currencies", List.of("usd")))
        .containsExactly("default.currencies: currency codes are three capital letters");
    assertThat(defaultErrors("currencies", List.of()))
        .containsExactly("default.currencies: " + CURRENCIES);
    assertThat(defaultErrors("currencies", "USD"))
        .containsExactly("default.currencies: " + CURRENCIES);
  }

  @Test
  void providersAreAllOrAllowOrDeny() {
    assertThat(defaultErrors("providers", "all")).isEmpty();
    assertThat(defaultErrors("providers", Map.of("allow", List.of("a")))).isEmpty();
    assertThat(defaultErrors("providers", Map.of("deny", List.of()))).isEmpty();
    assertThat(defaultErrors("providers", "some"))
        .containsExactly("default.providers: " + PROVIDERS);
    assertThat(defaultErrors("providers", 5)).containsExactly("default.providers: " + PROVIDERS);
    assertThat(defaultErrors("providers", Map.of("maybe", List.of("a"))))
        .containsExactly("default.providers: " + PROVIDERS);
    assertThat(defaultErrors("providers", Map.of("allow", "a")))
        .containsExactly("default.providers: " + PROVIDERS);
    assertThat(defaultErrors("providers", Map.of("allow", List.of("a"), "deny", List.of("b"))))
        .containsExactly("default.providers: " + PROVIDERS);
  }

  @Test
  void logsAreANonEmptyListOfNames() {
    assertThat(defaultErrors("logs", "GLOBAL"))
        .containsExactly("default.logs: must be a non-empty list of log names");
    assertThat(defaultErrors("logs", List.of()))
        .containsExactly("default.logs: must be a non-empty list of log names");
    assertThat(defaultErrors("logs", List.of("bad name")))
        .containsExactly("default.logs: log names must be letters, digits, - or _");
    assertThat(defaultErrors("logs", List.of("EU-west_1"))).isEmpty();
  }

  @Test
  void localOnlyIsABoolean() {
    assertThat(defaultErrors("local_only", "yes"))
        .containsExactly("default.local_only: must be true or false");
    assertThat(defaultErrors("local_only", false)).isEmpty();
  }

  @Test
  void retentionIsNilOrAPositiveNumberOfDays() {
    assertThat(defaultErrors("retention_days", 0))
        .containsExactly("default.retention_days: " + RETENTION);
    assertThat(defaultErrors("retention_days", 1.5))
        .containsExactly("default.retention_days: " + RETENTION);
    assertThat(defaultErrors("retention_days", "3")).hasSize(1);
    assertThat(defaultErrors("retention_days", 1)).isEmpty();
  }

  @Test
  void nilRetentionIsAcceptedAndMeansNoRule() {
    Map<String, Object> rule = new HashMap<>();
    rule.put("retention_days", null);
    rule.put("providers", "all");
    assertThat(build(Map.of("default", rule)).decide(Map.of()).retentionDays()).isNull();
    assertThat(Eligibility.fromMap(Map.of("default", rule))).isInstanceOf(Valid.class);
  }

  @Test
  void aValidRuleIsBuiltAndItsValuesApply() {
    Eligibility e =
        build(Map.of("default", Map.of("instruments", List.of("spot"), "logs", List.of("A", "B"))));

    assertThat(e.decide(Map.of("instrument", "forward")).permitted()).isFalse();
    assertThat(e.decide(Map.of("destinationRegion", "ZZ")).logs()).containsExactly("A", "B");
  }

  @Test
  void aProviderAllowListMayBeEmptyAndDeniesEveryone() {
    Eligibility e =
        build(Map.of("regions", Map.of("IN", Map.of("providers", Map.of("allow", List.of())))));
    assertThat(Eligibility.providerAllowed(e.decide(Map.of("destinationRegion", "IN")), "a"))
        .isFalse();
  }
}

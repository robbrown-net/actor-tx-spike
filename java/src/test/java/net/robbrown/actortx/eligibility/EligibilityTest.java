package net.robbrown.actortx.eligibility;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.robbrown.actortx.validation.Validated.Invalid;
import net.robbrown.actortx.validation.Validated.Valid;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class EligibilityTest {

  static Eligibility build(Map<String, Object> data) {
    return ((Valid<Eligibility>) Eligibility.fromMap(data)).value();
  }

  static List<String> errorsOf(Map<String, Object> data) {
    return switch (Eligibility.fromMap(data)) {
      case Valid<Eligibility> valid -> List.of();
      case Invalid<Eligibility> invalid -> invalid.errors();
    };
  }

  static final Map<String, Object> RULES =
      Map.of(
          "default",
          Map.of(
              "instruments",
              List.of("spot", "forward"),
              "customer_types",
              List.of("business", "retail")),
          "regions",
          Map.of(
              "IN",
              Map.of(
                  "instruments", List.of("spot"),
                  "providers", Map.of("allow", List.of("northwind", "cobalt")),
                  "logs", List.of("IN"),
                  "local_only", true,
                  "retention_days", 3650),
              "UK",
              Map.of("retention_days", 2555, "providers", Map.of("deny", List.of("zenith"))),
              "US",
              Map.of("customer_types", List.of("business"), "logs", List.of("GLOBAL", "US"))));

  @Test
  void anOpenEligibilityPermitsEverythingAndLogsGlobally() {
    Decision d = Eligibility.open().decide(Map.of("amount", 1));

    assertThat(d.permitted()).isTrue();
    assertThat(d.reasons()).isEmpty();
    assertThat(d.regions()).isEmpty();
    assertThat(d.logs()).containsExactly("GLOBAL");
    assertThat(d.retentionDays()).isNull();
    assertThat(d.instrument()).isEqualTo(Instrument.SPOT);
    assertThat(d.customerType()).isEqualTo(CustomerType.BUSINESS);
    assertThat(Eligibility.providerAllowed(d, "anyone")).isTrue();
  }

  @Test
  void instrumentAndCustomerTypeAreCheckedAgainstEveryRegionInvolved() {
    Eligibility e = build(RULES);

    assertThat(e.decide(Map.of("destinationRegion", "UK", "instrument", "forward")).permitted())
        .isTrue();

    Decision in = e.decide(Map.of("destinationRegion", "IN", "instrument", "forward"));
    assertThat(in.permitted()).isFalse();
    assertThat(in.reasons()).containsExactly("forward is not permitted in IN");

    // strictest wins on a corridor: UK allows forwards, IN does not
    Decision corridor =
        e.decide(Map.of("originRegion", "UK", "destinationRegion", "IN", "instrument", "forward"));
    assertThat(corridor.reasons()).containsExactly("forward is not permitted in IN");

    Decision retail = e.decide(Map.of("destinationRegion", "US", "customerType", "retail"));
    assertThat(retail.reasons()).containsExactly("retail customers are not permitted in US");

    assertThat(e.decide(Map.of("destinationRegion", "US", "customerType", "business")).permitted())
        .isTrue();
  }

  @Test
  void providersAllowListsDenyListsAndAllRulesOnACorridorApplyTogether() {
    Eligibility e = build(RULES);
    Decision inOnly = e.decide(Map.of("destinationRegion", "IN"));
    assertThat(Eligibility.providerAllowed(inOnly, "northwind")).isTrue();
    assertThat(Eligibility.providerAllowed(inOnly, "bluebird")).isFalse();
    assertThat(Eligibility.denialReason(inOnly, "bluebird"))
        .isEqualTo("bluebird is not eligible for the IN corridor");

    Decision uk = e.decide(Map.of("destinationRegion", "UK"));
    assertThat(Eligibility.providerAllowed(uk, "bluebird")).isTrue();
    assertThat(Eligibility.providerAllowed(uk, "zenith")).isFalse();

    Decision both = e.decide(Map.of("originRegion", "UK", "destinationRegion", "IN"));
    assertThat(Eligibility.providerAllowed(both, "cobalt")).isTrue();
    assertThat(Eligibility.providerAllowed(both, "bluebird")).isFalse();
    assertThat(Eligibility.providerAllowed(both, "zenith")).isFalse();
  }

  @Test
  void recordsGoToTheUnionOfLogsUnlessALocalOnlyRegionIsInvolved() {
    Eligibility e = build(RULES);
    assertThat(e.decide(Map.of("destinationRegion", "UK")).logs()).containsExactly("GLOBAL");
    assertThat(e.decide(Map.of("destinationRegion", "US")).logs()).containsExactly("GLOBAL", "US");
    assertThat(e.decide(Map.of("originRegion", "UK", "destinationRegion", "US")).logs())
        .containsExactly("GLOBAL", "US");

    // IN is local-only: the corridor's records stay in IN
    assertThat(e.decide(Map.of("originRegion", "US", "destinationRegion", "IN")).logs())
        .containsExactly("IN");
  }

  @Test
  void retentionIsTheLongestThatAppliesAndAnUnknownRegionUsesTheDefault() {
    Eligibility e = build(RULES);

    assertThat(e.decide(Map.of("originRegion", "UK", "destinationRegion", "IN")).retentionDays())
        .isEqualTo(3650);
    assertThat(e.decide(Map.of("destinationRegion", "UK")).retentionDays()).isEqualTo(2555);
    assertThat(e.decide(Map.of("destinationRegion", "BR")).retentionDays()).isNull();
    assertThat(e.decide(Map.of("destinationRegion", "BR")).logs()).containsExactly("GLOBAL");
  }

  @Test
  void aRegionThatRepeatsIsTouchedOnce() {
    Decision d = build(RULES).decide(Map.of("originRegion", "IN", "destinationRegion", "IN"));
    assertThat(d.regions()).containsExactly("IN");
  }

  @Test
  void anUnrecognisedInstrumentOrCustomerTypeIsRefusedRatherThanDefaulted() {
    Eligibility open = Eligibility.open();

    Decision instrument = open.decide(Map.of("instrument", "Forward"));
    assertThat(instrument.permitted()).isFalse();
    assertThat(instrument.reasons()).containsExactly("unknown instrument: Forward");
    assertThat(instrument.instrument()).isNull();

    Decision customer = open.decide(Map.of("customerType", "wholesale"));
    assertThat(customer.reasons()).containsExactly("unknown customerType: wholesale");
    assertThat(customer.customerType()).isNull();
  }

  private static Map<String, Object> badRules() {
    Map<String, Object> badDefault =
        Map.of(
            "instruments",
            List.of("swap"),
            "customer_types",
            List.of(),
            "providers",
            "some",
            "logs",
            List.of("bad name"),
            "local_only",
            "yes",
            "retention_days",
            0,
            "extra",
            1);
    Map<String, Object> badCn =
        Map.of("providers", Map.of("allow", List.of("a"), "deny", List.of("b")));
    return Map.of("bogus", 1, "default", badDefault, "regions", Map.of("IN", "nope", "CN", badCn));
  }

  @Test
  void rulesAreValidatedAndEveryErrorIsReported() {
    String text = String.join("\n", errorsOf(badRules()));

    assertThat(text)
        .contains("unknown key: bogus")
        .contains("default.instruments")
        .contains("default.customer_types")
        .contains("default.providers")
        .contains("default.logs: log names must be letters, digits, - or _")
        .contains("default.local_only: must be true or false")
        .contains("default.retention_days: must be nil or a positive number of days")
        .contains("default: unknown key extra")
        .contains("regions.IN: must be a map")
        .contains("regions.CN.providers");
  }

  @Test
  void aSingleBadFieldIsReportedOnceWithItsPath() {
    assertThat(errorsOf(Map.of("regions", Map.of("X", Map.of("providers", 5)))))
        .hasSize(1)
        .allMatch(e -> e.startsWith("regions.X.providers"));
    assertThat(errorsOf(Map.of("regions", Map.of("X", Map.of("logs", List.of()))))).hasSize(1);
    assertThat(Eligibility.fromMap(Map.of())).isInstanceOf(Valid.class);
  }

  @Nested
  class CurrencyRules {

    static final Map<String, Object> CURRENCY_RULES =
        Map.of(
            "regions",
            Map.of(
                "IN", Map.of("currencies", List.of("INR", "USD")),
                "CN", Map.of("currencies", List.of("CNY", "USD"))));

    @Test
    void bothCurrenciesMustBeAllowedInARegionAndTheReasonsSayWhich() {
      Eligibility e = build(CURRENCY_RULES);

      Decision ok =
          e.decide(
              Map.of(
                  "destinationRegion", "IN",
                  "sourceCurrency", "USD",
                  "destinationCurrency", "INR"));
      assertThat(ok.permitted()).isTrue();
      assertThat(ok.sourceCurrency()).isEqualTo("USD");
      assertThat(ok.destinationCurrency()).isEqualTo("INR");

      Decision bad =
          e.decide(
              Map.of(
                  "destinationRegion", "IN",
                  "sourceCurrency", "EUR",
                  "destinationCurrency", "GBP"));
      assertThat(bad.permitted()).isFalse();
      assertThat(bad.reasons())
          .containsExactly("EUR cannot be used in IN", "GBP cannot be used in IN");
    }

    @Test
    void inACorridorTheStrictestRegionWins() {
      Eligibility e = build(CURRENCY_RULES);
      Map<String, Object> request =
          Map.of(
              "originRegion", "IN",
              "destinationRegion", "CN",
              "sourceCurrency", "INR",
              "destinationCurrency", "USD");

      // INR is fine in IN but not in CN
      assertThat(e.decide(request).reasons()).containsExactly("INR cannot be used in CN");
      Map<String, Object> usd = new HashMap<>(request);
      usd.put("sourceCurrency", "USD");
      assertThat(e.decide(usd).permitted()).isTrue();
    }

    @Test
    void noCurrenciesNamedOrARegionWithoutACurrencyRulePermits() {
      Eligibility e = build(CURRENCY_RULES);
      assertThat(e.decide(Map.of("destinationRegion", "IN")).permitted()).isTrue();

      assertThat(
              e.decide(
                      Map.of(
                          "destinationRegion", "UK",
                          "sourceCurrency", "JPY",
                          "destinationCurrency", "KRW"))
                  .permitted())
          .isTrue();

      Eligibility open = build(Map.of("default", Map.of("currencies", "all")));
      assertThat(open.decide(Map.of("sourceCurrency", "JPY")).permitted()).isTrue();
    }

    @Test
    void currencyRulesAreValidated() {
      assertThat(errorsOf(Map.of("default", Map.of("currencies", List.of("usd")))))
          .containsExactly("default.currencies: currency codes are three capital letters");
      assertThat(errorsOf(Map.of("default", Map.of("currencies", List.of()))))
          .hasSize(1)
          .allMatch(e -> e.contains("must be \"all\" or a non-empty list of currency codes"));
      assertThat(errorsOf(Map.of("default", Map.of("currencies", 5)))).hasSize(1);
    }
  }
}

package net.robbrown.actortx.eligibility;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class RuleValuesTest {

  @Test
  void theUnrestrictedVariantsAllowEverything() {
    assertThat(new Currencies.All().allows("JPY")).isTrue();
    assertThat(new ProviderRule.All().permits("anyone")).isTrue();
  }

  @Test
  void aCurrencyListAllowsOnlyItsCodes() {
    Currencies only = new Currencies.Only(Set.of("INR"));
    assertThat(only.allows("INR")).isTrue();
    assertThat(only.allows("USD")).isFalse();
  }

  @Test
  void anAllowListPermitsOnlyItsProviders() {
    ProviderRule allow = new ProviderRule.Allow(Set.of("northwind"));
    assertThat(allow.permits("northwind")).isTrue();
    assertThat(allow.permits("bluebird")).isFalse();
  }

  @Test
  void aDenyListPermitsEveryoneButItsProviders() {
    ProviderRule deny = new ProviderRule.Deny(Set.of("zenith"));
    assertThat(deny.permits("zenith")).isFalse();
    assertThat(deny.permits("bluebird")).isTrue();
  }

  @Test
  void theDefaultRuleIsTheMostPermissiveOne() {
    Rule rule = Rule.defaults();
    assertThat(rule.instruments()).containsExactlyInAnyOrder(Instrument.values());
    assertThat(rule.customerTypes()).containsExactlyInAnyOrder(CustomerType.values());
    assertThat(rule.logs()).containsExactly("GLOBAL");
    assertThat(rule.localOnly()).isFalse();
    assertThat(rule.retentionDays()).isNull();
  }

  @Test
  void wireNamesRoundTripThroughParse() {
    assertThat(Instrument.FORWARD.wire()).isEqualTo("forward");
    assertThat(Instrument.parse("spot")).contains(Instrument.SPOT);
    assertThat(Instrument.parse("swap")).isEmpty();
    assertThat(CustomerType.RETAIL.wire()).isEqualTo("retail");
    assertThat(CustomerType.parse("business")).contains(CustomerType.BUSINESS);
    assertThat(CustomerType.parse("wholesale")).isEmpty();
  }
}

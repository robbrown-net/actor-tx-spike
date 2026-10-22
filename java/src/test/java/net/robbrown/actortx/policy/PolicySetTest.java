package net.robbrown.actortx.policy;

import static net.robbrown.actortx.policy.Results.errorsOf;
import static net.robbrown.actortx.policy.Results.valid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.robbrown.actortx.policy.PolicySet.Layer;
import net.robbrown.actortx.policy.PolicySet.Resolved;
import org.junit.jupiter.api.Test;

/** Port of the Elixir {@code PolicySetTest}: the layer cascade, entitlement and validation. */
final class PolicySetTest {

  private static Map<String, Object> data() {
    return Map.of(
        "global",
        Map.of(
            "weights",
            Map.of("cost", 0.6, "speed", 0.3, "reliability", 0.1),
            "max_bps_from_best",
            100),
        "merchants",
        Map.of(
            "acme",
                Map.of(
                    "overrides_allowed",
                    true,
                    "policy",
                    Map.of("weights", Map.of("cost", 0.9), "min_validity_ms", 5_000)),
            "cheapskate",
                Map.of(
                    "overrides_allowed", false, "policy", Map.of("weights", Map.of("cost", 1.0)))),
        "customers",
        Map.of(
            "vip-1",
            Map.of("policy", Map.of("weights", Map.of("speed", 1.0), "max_bps_from_best", 25))));
  }

  private static PolicySet set() {
    return valid(PolicySet.fromMap(data()));
  }

  @Test
  void unknownMerchantAndCustomerGetTheGlobalPolicy() {
    Resolved r = set().resolve(Map.of());

    assertThat(r.policy().maxBpsFromBest()).isEqualTo(100.0);
    assertThat(r.policy().weights().cost()).isCloseTo(0.6, within(1.0e-9));
    assertThat(r.layers()).containsExactly(new Layer.Global());

    Resolved unknown = set().resolve(Map.of("merchantId", "nobody", "customerId", "nobody"));
    assertThat(unknown.policy().maxBpsFromBest()).isEqualTo(100.0);
    assertThat(unknown.layers()).containsExactly(new Layer.Global());
  }

  @Test
  void anEntitledMerchantOverridesPartiallyAndInheritsTheRest() {
    Resolved r = set().resolve(Map.of("merchantId", "acme"));

    assertThat(r.layers()).containsExactly(new Layer.Global(), new Layer.Merchant("acme"));
    assertThat(r.policy().minValidityMs()).isEqualTo(5_000);
    assertThat(r.policy().maxBpsFromBest()).isEqualTo(100.0);
    assertThat(r.policy().weights().cost()).isCloseTo(0.9 / 1.3, within(1.0e-9));
    assertThat(r.policy().weights().speed()).isCloseTo(0.3 / 1.3, within(1.0e-9));
  }

  @Test
  void aMerchantThatHasNotPaidForOverridesIsIgnoredAndSaysSo() {
    Resolved r = set().resolve(Map.of("merchantId", "cheapskate"));

    assertThat(r.layers())
        .containsExactly(
            new Layer.Global(),
            new Layer.Ignored(new Layer.Merchant("cheapskate"), "not_entitled"));
    assertThat(r.policy().weights().cost()).isCloseTo(0.6, within(1.0e-9));
  }

  @Test
  void aCustomerLayerAppliesOnTopOfTheMerchantLayer() {
    Resolved r = set().resolve(Map.of("merchantId", "acme", "customerId", "vip-1"));

    assertThat(r.layers())
        .containsExactly(
            new Layer.Global(), new Layer.Merchant("acme"), new Layer.Customer("vip-1"));
    assertThat(r.policy().maxBpsFromBest()).isEqualTo(25.0);
    assertThat(r.policy().minValidityMs()).isEqualTo(5_000);
    assertThat(r.policy().weights().speed()).isGreaterThan(r.policy().weights().cost() * 0.9);
  }

  @Test
  void layersAreDescribedForHumans() {
    Resolved r = set().resolve(Map.of("merchantId", "cheapskate", "customerId", "vip-1"));

    assertThat(PolicySet.describe(r.layers()))
        .isEqualTo("global + merchant:cheapskate (ignored: not_entitled) + customer:vip-1");
    assertThat(
            PolicySet.describe(
                List.of(new Layer.Global(), new Layer.Merchant("a"), new Layer.Customer("b"))))
        .isEqualTo("global + merchant:a + customer:b");
  }

  @Test
  void badLayersAreRejectedUpFrontWithTheirLocation() {
    Map<String, Object> raw =
        Map.of(
            "global", Map.of("weights", Map.of("cost", -1)),
            "merchants", Map.of("m1", Map.of("policy", Map.of("max_latency_ms", 0))),
            "customers", Map.of("c1", Map.of("policy", Map.of("bogus", 1))));

    assertThat(errorsOf(PolicySet.fromMap(raw)))
        .containsExactlyInAnyOrder(
            "global: weights: must be non-negative numbers",
            "merchant:m1: weights: must be non-negative numbers",
            "merchant:m1: max_latency_ms: must be nil or a positive integer",
            "customer:c1: weights: must be non-negative numbers",
            "customer:c1: unknown key: bogus");
  }

  @Test
  void nonMapSectionsAreTreatedAsEmptyRatherThanCrashing() {
    Map<String, Object> raw = new HashMap<>();
    raw.put("merchants", null);
    raw.put("customers", "oops");
    raw.put("global", null);
    PolicySet set = valid(PolicySet.fromMap(raw));

    assertThat(set.resolve(Map.of("merchantId", "m", "customerId", "c")).layers())
        .containsExactly(new Layer.Global());

    Map<String, Object> entry = new HashMap<>();
    entry.put("overrides_allowed", true);
    entry.put("policy", null);
    PolicySet withNullPolicy = valid(PolicySet.fromMap(Map.of("merchants", Map.of("m", entry))));

    assertThat(withNullPolicy.resolve(Map.of("merchantId", "m")).layers())
        .containsExactly(new Layer.Global(), new Layer.Merchant("m"));
  }

  @Test
  void anEmptyDefinitionIsValidAndRequestIdsMatchByTheirText() {
    PolicySet empty = valid(PolicySet.fromMap(Map.of()));
    assertThat(empty.resolve(Map.of()).layers()).containsExactly(new Layer.Global());

    PolicySet numeric =
        valid(
            PolicySet.fromMap(
                Map.of(
                    "global", Map.of("max_bps_from_best", 10),
                    "merchants",
                        Map.of(
                            "7",
                            Map.of(
                                "overrides_allowed",
                                true,
                                "policy",
                                Map.of("min_validity_ms", 1))))));

    Resolved r = numeric.resolve(Map.of("merchantId", 7L));

    assertThat(r.policy().maxBpsFromBest()).isEqualTo(10.0);
    assertThat(r.policy().minValidityMs()).isEqualTo(1);
  }

  @Test
  void layersThatEachValidCanStillCombineToAnInvalidPolicyAndResolveFailsLoudly() {
    PolicySet set =
        valid(
            PolicySet.fromMap(
                Map.of(
                    "global", Map.of("weights", Map.of("cost", 1)),
                    "merchants",
                        Map.of(
                            "m",
                            Map.of(
                                "overrides_allowed",
                                true,
                                "policy",
                                Map.of("weights", Map.of("cost", 0, "speed", 1)))),
                    "customers",
                        Map.of(
                            "c",
                            Map.of(
                                "policy",
                                Map.of("weights", Map.of("speed", 0, "reliability", 0)))))));

    assertThatThrownBy(() -> set.resolve(Map.of("merchantId", "m", "customerId", "c")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not a valid policy");
  }
}

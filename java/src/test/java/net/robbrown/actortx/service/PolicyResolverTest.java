package net.robbrown.actortx.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import net.robbrown.actortx.policy.PolicySet;
import net.robbrown.actortx.policy.PolicySet.Resolved;
import net.robbrown.actortx.validation.Validated;
import org.junit.jupiter.api.Test;

class PolicyResolverTest {

  private static PolicyResolver resolver() {
    Map<String, Object> data =
        Map.of(
            "global", Map.of("weights", Map.of("cost", 1)),
            "merchants",
                Map.of(
                    "acme",
                        Map.of(
                            "overrides_allowed",
                            true,
                            "policy",
                            Map.of("weights", Map.of("speed", 1))),
                    "globex",
                        Map.of(
                            "overrides_allowed",
                            false,
                            "policy",
                            Map.of("weights", Map.of("speed", 1)))),
            "customers", Map.of("vip", Map.of("policy", Map.of("max_bps_from_best", 50))));
    return new PolicyResolver(((Validated.Valid<PolicySet>) PolicySet.fromMap(data)).value());
  }

  @Test
  void theCascadeIsGlobalThenMerchantThenCustomer() {
    Resolved resolved = resolver().resolve("acme", "vip");

    assertThat(PolicySet.describe(resolved.layers()))
        .isEqualTo("global + merchant:acme + customer:vip");
    assertThat(resolved.policy().maxBpsFromBest()).isEqualTo(50.0);
  }

  @Test
  void aMerchantThatHasNotPaidForOverridesIsIgnoredAndSaidSo() {
    Resolved resolved = resolver().resolve("globex", null);

    assertThat(PolicySet.describe(resolved.layers()))
        .isEqualTo("global + merchant:globex (ignored: not_entitled)");
  }

  @Test
  void nobodyInParticularGetsTheGlobalPolicy() {
    assertThat(PolicySet.describe(resolver().resolve(null, null).layers())).isEqualTo("global");
    assertThat(List.of(resolver().resolve("unknown", "nobody").layers())).hasSize(1);
  }
}

package net.robbrown.actortx.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import net.robbrown.actortx.eligibility.Decision;
import net.robbrown.actortx.eligibility.Eligibility;
import net.robbrown.actortx.validation.Validated;
import org.junit.jupiter.api.Test;

class EligibilityServiceTest {

  @Test
  void decidesUnderTheConfiguredRules() {
    Eligibility rules =
        ((Validated.Valid<Eligibility>)
                Eligibility.fromMap(
                    Map.of(
                        "regions", Map.of("IN", Map.of("instruments", java.util.List.of("spot"))))))
            .value();
    EligibilityService service = new EligibilityService(rules);

    Decision forward = service.decide(Map.of("destinationRegion", "IN", "instrument", "forward"));
    assertThat(forward.permitted()).isFalse();
    assertThat(forward.reasons()).containsExactly("forward is not permitted in IN");

    assertThat(service.decide(Map.of("destinationRegion", "IN", "instrument", "spot")).permitted())
        .isTrue();
  }
}

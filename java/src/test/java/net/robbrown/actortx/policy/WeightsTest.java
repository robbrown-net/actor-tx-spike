package net.robbrown.actortx.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class WeightsTest {

  @Test
  void theThreeStandardWeightsAreReadByName() {
    Weights weights = new Weights(0.5, 0.25, 0.25);

    assertThat(weights.cost()).isEqualTo(0.5);
    assertThat(weights.speed()).isEqualTo(0.25);
    assertThat(weights.reliability()).isEqualTo(0.25);
    assertThat(weights.values().keySet()).containsExactly("cost", "speed", "reliability");
  }

  @Test
  void anyCriterionIsReadByNameAndAbsentOnesWeighZero() {
    Weights weights = new Weights(Map.of("liquidity", 1.0));

    assertThat(weights.get("liquidity")).isEqualTo(1.0);
    assertThat(weights.cost()).isZero();
    assertThat(weights.speed()).isZero();
    assertThat(weights.reliability()).isZero();
  }

  @Test
  void theValuesCannotChangeAfterwards() {
    Map<String, Double> source = new java.util.HashMap<>(Map.of("cost", 1.0));
    Weights weights = new Weights(source);
    source.put("cost", 2.0);

    assertThat(weights.cost()).isEqualTo(1.0);
    assertThatThrownBy(() -> weights.values().put("cost", 3.0))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}

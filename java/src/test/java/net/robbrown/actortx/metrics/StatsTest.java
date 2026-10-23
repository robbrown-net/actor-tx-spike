package net.robbrown.actortx.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class StatsTest {

  @Test
  void percentileUsesNearestRankAndToleratesUnsortedInput() {
    List<Double> values = List.of(50.0, 10.0, 40.0, 20.0, 30.0);

    assertThat(Stats.percentile(values, 50)).isEqualTo(30.0);
    assertThat(Stats.percentile(values, 100)).isEqualTo(50.0);
    assertThat(Stats.percentile(values, 0)).isEqualTo(10.0);
    assertThat(Stats.percentile(List.of(), 95)).isEqualTo(0.0);
  }

  @Test
  void summaryAndMean() {
    assertThat(Stats.summary(List.of(3.0, 1.0, 2.0)))
        .isEqualTo(new Stats.Summary(2.0, 3.0, 3.0, 3.0, 3));
    assertThat(Stats.summary(List.of())).isEqualTo(new Stats.Summary(0.0, 0.0, 0.0, 0.0, 0));
    assertThat(Stats.mean(List.of(1.0, 2.0, 3.0))).isEqualTo(2.0);
    assertThat(Stats.mean(List.of())).isEqualTo(0.0);
  }

  @Test
  void maxAndSumOfNoValuesAreZero() {
    assertThat(Stats.max(List.of())).isEqualTo(0.0);
    assertThat(Stats.max(List.of(1.0, 4.0, 2.0))).isEqualTo(4.0);
    assertThat(Stats.sum(List.of())).isEqualTo(0.0);
    assertThat(Stats.sum(List.of(1.5, 2.5))).isEqualTo(4.0);
  }
}

package net.robbrown.actortx.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class PercentilesTest {

  @Test
  void nothingToMeasureIsAllZero() {
    assertThat(Percentiles.of(List.of())).isEqualTo(new Percentiles(0, 0, 0, 0));
  }

  @Test
  void nearestRankOverAHundredValues() {
    List<Long> values = LongStream.rangeClosed(1, 100).boxed().toList();

    assertThat(Percentiles.of(values)).isEqualTo(new Percentiles(50, 90, 99, 100));
  }

  @Test
  void aSingleValueIsEveryPercentile() {
    assertThat(Percentiles.of(List.of(7L))).isEqualTo(new Percentiles(7, 7, 7, 7));
  }

  @Test
  void theOrderOfTheValuesDoesNotMatter() {
    assertThat(Percentiles.of(List.of(70L, 30L))).isEqualTo(new Percentiles(30, 70, 70, 70));
  }
}

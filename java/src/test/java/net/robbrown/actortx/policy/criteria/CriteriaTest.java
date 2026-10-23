package net.robbrown.actortx.policy.criteria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class CriteriaTest {

  @Test
  void theStandardRegistryHasCostSpeedAndReliabilityInThatOrder() {
    assertThat(Criteria.standard().names()).containsExactly("cost", "speed", "reliability");
    assertThat(Criteria.standard().all())
        .hasExactlyElementsOfTypes(
            CostCriterion.class, SpeedCriterion.class, ReliabilityCriterion.class);
  }

  @Test
  void knowsOnlyItsOwnNames() {
    assertThat(Criteria.standard().knows("cost")).isTrue();
    assertThat(Criteria.standard().knows("luck")).isFalse();
  }

  @Test
  void aDuplicateNameIsRejected() {
    assertThatThrownBy(() -> Criteria.of(List.of(new CostCriterion(), new CostCriterion())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("duplicate criterion: cost");
  }

  @Test
  void aRegistryOfNothingIsEmpty() {
    assertThat(Criteria.of(List.of()).names()).isEmpty();
  }

  @Test
  void theRegistryIsImmutable() {
    assertThatThrownBy(() -> Criteria.standard().names().add("x"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void aSpanPlacesValuesWithinTheField() {
    Span span = new Span(10, 20);

    assertThat(span.higherIsBetter(20)).isEqualTo(1.0);
    assertThat(span.higherIsBetter(15)).isEqualTo(0.5);
    assertThat(span.lowerIsBetter(10)).isEqualTo(1.0);
    assertThat(span.lowerIsBetter(20)).isEqualTo(0.0);
  }

  @Test
  void anEmptySpanScoresEverythingFull() {
    assertThat(new Span(7, 7).higherIsBetter(7)).isEqualTo(1.0);
    assertThat(new Span(7, 7).lowerIsBetter(7)).isEqualTo(1.0);
  }
}

package net.robbrown.actortx.economics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CostsTest {

  private static final long GIB = 1_073_741_824L;

  private static final RateCard CARD = RateCard.defaults();

  @Test
  void eachResourceIsPricedFromTheRateCard() {
    assertThat(Costs.price(Usage.none().reductions(2_000_000L).build(), CARD))
        .isEqualTo(Cost.of(222_000L, 0L, 0L, 0L, 0L));
    assertThat(Costs.price(Usage.none().memoryBytes(GIB).durationMs(1_000L).build(), CARD).memory())
        .isEqualTo(1_389_000L);
    assertThat(Costs.price(Usage.none().bytesOut(GIB).build(), CARD).network())
        .isEqualTo(90_000_000_000L);
    assertThat(Costs.price(Usage.none().bytesIn(GIB).build(), CARD).network()).isZero();
    assertThat(Costs.price(Usage.none().stateChanges(10L).build(), CARD).storage())
        .isEqualTo(2_500_000L);
    assertThat(Costs.price(Usage.none().providerCalls(6L).build(), CARD).providerCalls())
        .isEqualTo(300_000_000L);
  }

  @Test
  void theTotalIsTheSumOfTheParts() {
    Measurement usage =
        Usage.none()
            .reductions(2_000_000L)
            .memoryBytes(GIB)
            .durationMs(1_000L)
            .providerCalls(6L)
            .bytesOut(GIB)
            .bytesIn(GIB)
            .stateChanges(10L)
            .build();

    assertThat(Costs.price(usage, CARD).total()).isEqualTo(90_304_111_000L);
  }

  @Test
  void pricesAreOverridableSoScenariosAreOneLiners() {
    RateCard doubledEgress =
        new RateCard(
            111_000L, 1_389_000L, 180_000_000_000L, 5L, 250_000L, 50_000_000L, 83_200_000_000L);
    Measurement usage = Usage.none().bytesOut(GIB).bytesIn(GIB).build();

    assertThat(Costs.price(usage, doubledEgress).network()).isEqualTo(180_000_000_005L);
  }

  @Test
  void platformCostAccruesWithTimeNotTraffic() {
    assertThat(Costs.platform(CARD, 3_600_000L)).isEqualTo(83_200_000_000L);
    assertThat(Costs.platform(CARD, 1_800_000L)).isEqualTo(41_600_000_000L);
    assertThat(Costs.platform(CARD, 0L)).isZero();
  }

  @Test
  void aCentIsTenMillionPicoUnits() {
    assertThat(Costs.PICOS_PER_MINOR).isEqualTo(10_000_000_000L);
  }

  @Test
  void roundingIsHalfEvenPerResource() {
    RateCard perByte = new RateCard(0L, 0L, 1L, 0L, 0L, 0L, 0L);

    assertThat(Costs.price(Usage.none().bytesOut(GIB / 2).build(), perByte).network()).isZero();
    assertThat(Costs.price(Usage.none().bytesOut(GIB / 2 + 1).build(), perByte).network())
        .isEqualTo(1L);
  }

  @Test
  void aPriceTooLargeForALongIsRefusedNotWrapped() {
    RateCard steep = new RateCard(Long.MAX_VALUE, 0L, 0L, 0L, 0L, 0L, 0L);
    Measurement huge = Usage.none().reductions(Long.MAX_VALUE).build();

    assertThatThrownBy(() -> Costs.price(huge, steep)).isInstanceOf(ArithmeticException.class);
  }
}

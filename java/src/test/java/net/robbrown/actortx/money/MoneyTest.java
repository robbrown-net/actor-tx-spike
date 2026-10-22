package net.robbrown.actortx.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyTest {

  @Test
  void decimalsConvertExactlyNotViaBinaryFloatingPoint() {
    assertThat(Money.rate(1.0877)).isEqualTo(108_770_000L);
    assertThat(Money.rate(1.082)).isEqualTo(108_200_000L);
    assertThat(Money.rate("1.0820")).isEqualTo(108_200_000L);
    assertThat(Money.rate(1L)).isEqualTo(100_000_000L);
    assertThat(Money.rate(".5")).isEqualTo(50_000_000L);
    assertThat(Money.rate("1.5e-1")).isEqualTo(15_000_000L);
    assertThat(Money.rate("2e2")).isEqualTo(20_000_000_000L);
    assertThat(Money.minor(12.34)).isEqualTo(1234L);
    assertThat(Money.minor("-0.07")).isEqualTo(-7L);
    assertThat(Money.minor(0.1 + 0.2)).isEqualTo(30L);
  }

  @Test
  void excessPrecisionRoundsHalfEven() {
    assertThat(Money.minor("0.125")).isEqualTo(12L);
    assertThat(Money.minor("0.135")).isEqualTo(14L);
    assertThat(Money.minor("0.1251")).isEqualTo(13L);
    assertThat(Money.minor("0.1249")).isEqualTo(12L);
  }

  @Test
  void convertAppliesARateToMinorUnitsWithHalfEvenRounding() {
    assertThat(Money.convert(100_000, Money.rate(1.082))).isEqualTo(108_200L);
    assertThat(Money.convert(1, Money.rate(0.5))).isZero();
    assertThat(Money.convert(3, Money.rate(0.5))).isEqualTo(2L);
    assertThat(Money.convert(5, Money.rate(0.5))).isEqualTo(2L);
    assertThat(Money.convert(-3, Money.rate(0.5))).isEqualTo(-2L);
    assertThat(Money.convert(7, Money.rate(0.3))).isEqualTo(2L);
  }

  @Test
  void convertDoesNotOverflowOnLargeAmounts() {
    long trillion = 1_000_000_000_000_00L;
    assertThat(Money.convert(trillion, Money.rate(2L))).isEqualTo(2 * trillion);
  }

  @Test
  void divHalfEvenCoversBelowAboveAndExactlyHalf() {
    assertThat(Money.divHalfEven(1, 4)).isZero();
    assertThat(Money.divHalfEven(3, 4)).isEqualTo(1L);
    assertThat(Money.divHalfEven(2, 4)).isZero();
    assertThat(Money.divHalfEven(6, 4)).isEqualTo(2L);
    assertThat(Money.divHalfEven(-2, 4)).isZero();
    assertThat(Money.divHalfEven(-6, 4)).isEqualTo(-2L);
  }

  @Test
  void divHalfEvenRefusesAZeroOrNegativeDivisor() {
    assertThatThrownBy(() -> Money.divHalfEven(1, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Money.divHalfEven(1, -3)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void formattingForDisplay() {
    assertThat(Money.format(108_500)).isEqualTo("1085.00");
    assertThat(Money.format(-5)).isEqualTo("-0.05");
    assertThat(Money.format(0)).isEqualTo("0.00");
    assertThat(Money.formatRate(Money.rate(1.082))).isEqualTo("1.0820");
    assertThat(Money.formatRate(Money.rate(1.08765432))).isEqualTo("1.08765432");
    assertThat(Money.rateToDouble(108_200_000)).isEqualTo(1.082);
    assertThat(Money.toMajor(1234)).isEqualTo(12.34);
    assertThat(Money.RATE_SCALE).isEqualTo(100_000_000L);
  }

  @Test
  void notANumberIsRefused() {
    assertThatThrownBy(() -> Money.minor("twelve")).isInstanceOf(NumberFormatException.class);
  }
}

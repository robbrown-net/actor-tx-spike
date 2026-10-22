package net.robbrown.actortx.money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Exact money arithmetic. No floating point ever holds money.
 *
 * <ul>
 *   <li><b>amounts and fees</b> are {@code long}s in <em>minor units</em> (cents)
 *   <li><b>rates</b> are {@code long}s scaled by 10^8 ({@code 1.0820} is {@code 108_200_000})
 * </ul>
 *
 * Decimal input is converted exactly at the edge through its shortest decimal representation, so
 * {@code 1.0877} becomes {@code 108_770_000} and not a binary approximation. Rounding is half-even
 * (banker's), the convention for financial conversions.
 */
public final class Money {

  /** Scale factor of a rate (10^8). */
  public static final long RATE_SCALE = 100_000_000L;

  private static final long MINOR_SCALE = 100L;
  private static final long FRACTION_DIGITS = 8;
  private static final int MIN_RATE_DECIMALS = 4;

  private Money() {}

  /** A decimal string such as {@code "1.0820"} or {@code "1.5e-1"} as a scaled-integer rate. */
  public static long rate(String decimal) {
    return scaled(new BigDecimal(decimal), RATE_SCALE);
  }

  /** A decimal as a scaled-integer rate, through its shortest decimal form. */
  public static long rate(double decimal) {
    return scaled(BigDecimal.valueOf(decimal), RATE_SCALE);
  }

  /** A whole-number rate (such as 1) as a scaled-integer rate. */
  public static long rate(long whole) {
    return Math.multiplyExact(whole, RATE_SCALE);
  }

  /** Major units as a decimal string ({@code "12.34"}) to integer minor units (1234). */
  public static long minor(String decimal) {
    return scaled(new BigDecimal(decimal), MINOR_SCALE);
  }

  /** Major units as a decimal (12.34) to integer minor units (1234), through its shortest form. */
  public static long minor(double decimal) {
    return scaled(BigDecimal.valueOf(decimal), MINOR_SCALE);
  }

  /** {@code amountMinor * rate}, rounded half-even to a whole minor unit. */
  public static long convert(long amountMinor, long rate) {
    BigInteger product = BigInteger.valueOf(amountMinor).multiply(BigInteger.valueOf(rate));
    return halfEven(new BigDecimal(product), BigDecimal.valueOf(RATE_SCALE));
  }

  /** Integer division rounding half to even. */
  public static long divHalfEven(long n, long d) {
    if (d <= 0) {
      throw new IllegalArgumentException("the divisor must be positive: " + d);
    }
    return halfEven(BigDecimal.valueOf(n), BigDecimal.valueOf(d));
  }

  /** A rate for display, e.g. {@code 1.082}. Display only; never compute with it. */
  public static double rateToDouble(long rate) {
    return (double) rate / RATE_SCALE;
  }

  /** Minor units for display, e.g. {@code 1085.0} for 108_500. Display only. */
  public static double toMajor(long minor) {
    return (double) minor / MINOR_SCALE;
  }

  /** Minor units as a decimal string with two places: {@code "1085.00"}, {@code "-0.05"}. */
  public static String format(long minor) {
    String sign = minor < 0 ? "-" : "";
    long abs = Math.abs(minor);
    return "%s%d.%02d".formatted(sign, abs / MINOR_SCALE, abs % MINOR_SCALE);
  }

  /** A rate with up to 8 decimals, trailing zeros trimmed to at least 4: {@code "1.0820"}. */
  public static String formatRate(long rate) {
    String fraction = "%0" + FRACTION_DIGITS + "d";
    String digits = fraction.formatted(rate % RATE_SCALE).replaceAll("0+$", "");
    String padded = digits + "0".repeat(Math.max(0, MIN_RATE_DECIMALS - digits.length()));
    return (rate / RATE_SCALE) + "." + padded;
  }

  private static long scaled(BigDecimal value, long factor) {
    return halfEven(value.multiply(BigDecimal.valueOf(factor)), BigDecimal.ONE);
  }

  private static long halfEven(BigDecimal numerator, BigDecimal denominator) {
    return numerator.divide(denominator, 0, RoundingMode.HALF_EVEN).longValueExact();
  }
}

package net.robbrown.actortx.economics;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * The pure cost model: prices measured usage with a {@link RateCard}, and the always-on platform
 * cost of elapsed time. Every figure is in pico-units, rounded half-even per resource.
 *
 * <p>Products are exact (computed as big integers before the division, as the Elixir version's
 * arbitrary-precision integers do). Only a final price too large for a {@code long} is refused,
 * with an {@link ArithmeticException}; nothing is ever silently wrapped.
 */
public final class Costs {

  /** Pico-units in one minor unit: a cent is 10,000,000,000 pico-units. */
  public static final long PICOS_PER_MINOR = 10_000_000_000L;

  private static final long GIB = 1_073_741_824L;
  private static final long GIB_MS = GIB * 1_000L;
  private static final long MILLION = 1_000_000L;
  private static final long MS_PER_HOUR = 3_600_000L;

  private Costs() {}

  /** Prices one transaction's measured usage. */
  public static Cost price(Measurement usage, RateCard card) {
    return Cost.of(
        scale(times(usage.reductions(), card.cpuPicosPerMillionReductions()), MILLION),
        memory(usage, card),
        network(usage, card),
        Math.multiplyExact(usage.providerCalls(), card.providerCallPicos()),
        Math.multiplyExact(usage.stateChanges(), card.stateChangePicos()));
  }

  /** The always-on platform cost of running for {@code elapsedMs}; it accrues with time only. */
  public static long platform(RateCard card, long elapsedMs) {
    return scale(times(elapsedMs, card.platformPicosPerHour()), MS_PER_HOUR);
  }

  private static long memory(Measurement usage, RateCard card) {
    BigInteger byteMs = times(usage.memoryBytes(), usage.durationMs());
    return scale(byteMs.multiply(BigInteger.valueOf(card.memoryPicosPerGibSecond())), GIB_MS);
  }

  private static long network(Measurement usage, RateCard card) {
    return scale(times(usage.bytesOut(), card.networkPicosPerGibOut()), GIB)
        + scale(times(usage.bytesIn(), card.networkPicosPerGibIn()), GIB);
  }

  private static BigInteger times(long a, long b) {
    return BigInteger.valueOf(a).multiply(BigInteger.valueOf(b));
  }

  /** The exact quotient, rounded half to even, as a {@code long}. */
  private static long scale(BigInteger numerator, long denominator) {
    return new BigDecimal(numerator)
        .divide(BigDecimal.valueOf(denominator), 0, RoundingMode.HALF_EVEN)
        .longValueExact();
  }
}

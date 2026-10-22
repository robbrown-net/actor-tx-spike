package net.robbrown.actortx.provider;

/**
 * A firm offer from a provider: a rate (scaled by 10^8) and a fee (minor units) that expire.
 *
 * @param provider who quoted
 * @param requestId the request it answers
 * @param rate scaled by {@code Money.RATE_SCALE}
 * @param fee minor units
 * @param expiresAtMs wall-clock milliseconds after which the quote must not be accepted
 */
public record Quote(String provider, String requestId, long rate, long fee, long expiresAtMs) {

  /** Has this quote expired at {@code nowMs}? */
  public boolean expired(long nowMs) {
    return nowMs >= expiresAtMs;
  }
}

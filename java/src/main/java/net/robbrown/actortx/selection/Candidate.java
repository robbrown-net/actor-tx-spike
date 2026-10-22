package net.robbrown.actortx.selection;

import net.robbrown.actortx.provider.HealthState;
import net.robbrown.actortx.provider.Quote;

/**
 * A quote that is being judged: with its net value to the customer, how fast the provider answered,
 * how long it stays valid, the provider's health, and (once ranked) a score.
 *
 * @param providerId the provider
 * @param quote the offer
 * @param net {@code amount * rate - fee} in minor units
 * @param latencyMs how long the provider took to answer
 * @param validityMs how much of the quote's life was left when it was seen
 * @param state the provider's health when asked
 * @param score higher is better; 0 until ranked
 */
public record Candidate(
    String providerId,
    Quote quote,
    long net,
    long latencyMs,
    long validityMs,
    HealthState state,
    double score) {

  /** The same candidate with a score. */
  public Candidate withScore(double newScore) {
    return new Candidate(providerId, quote, net, latencyMs, validityMs, state, newScore);
  }
}

package net.robbrown.actortx.provider;

import java.time.Duration;

/**
 * What selection needs from a provider: its health state, and a way to ask for a quote within a
 * time budget. {@link ProviderServer} is the real one; tests substitute misbehaving ones.
 */
public interface QuoteSource {

  /** Our current view of the provider's health. */
  HealthState state();

  /** Ask for a quote; an {@code Error("overloaded")} answer means nothing was sent. */
  Answer<Quote> requestQuote(QuoteRequest request, Duration timeout);
}

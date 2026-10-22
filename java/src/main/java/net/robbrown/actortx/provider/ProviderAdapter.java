package net.robbrown.actortx.provider;

/**
 * Adapter for a third-party provider facade (FX quote and payment).
 *
 * <p>We do not control providers: they may be fast, slow, expensive, degraded or stalled, and may
 * change behaviour without notice. Adapters normalise every response into {@link Answer}.
 *
 * <p>{@code requestQuote} has no side effect. The effectful steps are {@code acceptQuote} (lock the
 * rate), {@code authorise} (hold funds), {@code pay} and {@code settle}. Each takes an idempotency
 * key and must be idempotent for it. Each of the first three has an undo keyed by the same key:
 * {@code cancelAccept}, {@code voidAuthorisation}, {@code refund}. Undos must also be idempotent,
 * and safe to call for a key whose effect never happened.
 *
 * <p>{@code status} looks up what a provider holds for an effect. It may be slow or stale: callers
 * treat {@code NotFound} as "not known yet", not "did not happen".
 */
public interface ProviderAdapter {

  /** Price a request. No side effect. */
  Answer<Quote> requestQuote(QuoteRequest request);

  /** Lock the quoted rate. */
  Answer<Receipt> acceptQuote(Quote quote, String key);

  /** Hold the funds. */
  Answer<Receipt> authorise(Quote quote, String key);

  /** Make the payment. */
  Answer<Receipt> pay(Quote quote, String key);

  /** Final settlement of a payment already made. Has no undo. */
  Answer<Receipt> settle(String key);

  /** Undo {@link #acceptQuote}. */
  Answer<Receipt> cancelAccept(String key);

  /** Undo {@link #authorise}. */
  Answer<Receipt> voidAuthorisation(String key);

  /** Undo {@link #pay}. */
  Answer<Receipt> refund(String key);

  /** What the provider holds for an operation under a key. */
  Answer<Receipt> status(Operation op, String key);

  /** A health probe. */
  Answer<ProbeInfo> probe();
}

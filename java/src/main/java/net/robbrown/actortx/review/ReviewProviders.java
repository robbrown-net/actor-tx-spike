package net.robbrown.actortx.review;

import net.robbrown.actortx.provider.Answer;
import net.robbrown.actortx.provider.Operation;
import net.robbrown.actortx.provider.Receipt;

/**
 * How the review workflow talks to the providers: the only thing it needs from them. The host wires
 * this to the running {@code ProviderServer}s; tests use stubs.
 *
 * <p>An implementation must be safe to call from the review service's thread, and must apply its
 * own call timeout (the review never waits forever on a provider).
 */
public interface ReviewProviders {

  /** Is a provider with this name running and reachable from the review? */
  boolean available(String provider);

  /**
   * What a provider holds for an operation under a key (read-only). A {@code NotFound} answer means
   * "not known yet", not "did not happen".
   *
   * @param provider the provider's name
   * @param op the operation whose effect is being looked up
   * @param key the idempotency key the effect was made under
   * @return the provider's answer
   */
  Answer<Receipt> status(String provider, Operation op, String key);

  /**
   * Send an effectful step again under its original key. Only the operations a person may retry are
   * sent this way; the provider's idempotency makes a repeat harmless.
   *
   * @param provider the provider's name
   * @param op the step to send again
   * @param key the step's original idempotency key
   * @return the provider's answer
   */
  Answer<Receipt> invoke(String provider, Operation op, String key);
}

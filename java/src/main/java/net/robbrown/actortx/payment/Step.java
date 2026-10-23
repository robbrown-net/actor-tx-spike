package net.robbrown.actortx.payment;

import java.util.Optional;
import net.robbrown.actortx.provider.Answer;
import net.robbrown.actortx.provider.Operation;
import net.robbrown.actortx.provider.ProviderAdapter;
import net.robbrown.actortx.provider.Quote;
import net.robbrown.actortx.provider.Receipt;

/**
 * One effectful step of a payment, as the workflow engine sees it. A step knows what it is called,
 * what it asks the provider to do, how to undo it, and what its failure means; the engine knows
 * none of those things. To add a step, write a {@code Step} and put it in a {@link Workflow}.
 *
 * <p>What a step declares drives the safety rules:
 *
 * <ul>
 *   <li>a step with <b>no compensation</b> cannot be undone. Once one is done the engine will not
 *       fail over to another provider, and a later failure goes to a person;
 *   <li>a step <b>confirmed by status</b> completes asynchronously: the provider's first answer
 *       only says it was accepted, and the engine asks until it is confirmed.
 * </ul>
 */
public interface Step {

  /** What the provider is asked to do. */
  Operation operation();

  /** The part of the idempotency key that names this step ({@code "<tx>:<provider>:<keyName>"}). */
  String keyName();

  /** What it is called in the story ("accept the quote"). */
  String verb();

  /** The phase the transaction is in while this step runs. */
  Phase phase();

  /** The word used in reasons ("settlement" gives {@code settlement_failed}). */
  String label();

  /** How to undo it; empty if it cannot be undone. */
  Optional<Compensation> compensation();

  /** Does it complete asynchronously, confirmed by asking the provider for its status? */
  default boolean confirmedByStatus() {
    return false;
  }

  /** Can it be undone? */
  default boolean reversible() {
    return compensation().isPresent();
  }

  /** Ask the provider to do it. The default is the provider's generic entry point. */
  default Answer<Receipt> perform(ProviderAdapter adapter, Quote quote, String key) {
    return adapter.perform(operation(), quote, key);
  }
}

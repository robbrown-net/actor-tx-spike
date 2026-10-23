package net.robbrown.actortx.bulk;

import net.robbrown.actortx.service.PaymentCommand;

/**
 * Sends one payment command into the system and says what came of it. The bulk sender and the load
 * tool talk only to this seam, so tests use stubs and the real payment service is wired in later.
 */
@FunctionalInterface
public interface Submitter {

  /**
   * Submit one command. Should answer promptly: the sender waits for the answer before its next
   * step.
   *
   * @param command the payment to send
   * @return accepted (with its id and how it will end), invalid, or busy
   */
  Submission submit(PaymentCommand command);
}

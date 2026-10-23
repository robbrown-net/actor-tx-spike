package net.robbrown.actortx.review;

/**
 * A review action the queue's current state does not allow: the transaction is not waiting for
 * review, is already resolved, has no provider or its provider is not available, or the audit log
 * cannot take the record. Nothing was done.
 */
public final class ReviewException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * An exception with the sentence a person reads.
   *
   * @param message what is not allowed, and why
   */
  public ReviewException(String message) {
    super(message);
  }
}

package net.robbrown.actortx.audit;

/**
 * The audit trail refuses: a log whose chain is broken, a log held by a live writer, a record with
 * nowhere lawful to go, or a write that could not be made. Callers must treat it as fail closed.
 */
public class AuditException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * A refusal with a reason.
   *
   * @param message why the audit trail refused
   */
  public AuditException(String message) {
    super(message);
  }

  /**
   * A refusal caused by a failure underneath.
   *
   * @param message why the audit trail refused
   * @param cause the failure underneath
   */
  public AuditException(String message, Throwable cause) {
    super(message, cause);
  }
}

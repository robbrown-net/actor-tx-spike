package net.robbrown.actortx.replay;

/**
 * A day that cannot be loaded or replayed: a missing or unreadable log, a line that is not JSON, a
 * log whose hash chain fails verification, or a what-if policy that is not a valid policy. Replay
 * refuses rather than guessing.
 */
public class ReplayException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Why the day could not be replayed. */
  public enum Reason {
    /** The log file does not exist. */
    NO_SUCH_LOG,
    /** A line of the log is not a JSON object. */
    MALFORMED_LOG,
    /** The hash chain of the log fails verification. */
    INTEGRITY,
    /** The file cannot be read. */
    UNREADABLE,
    /** A policy override does not make a valid policy. */
    INVALID_POLICY
  }

  private final Reason reason;

  /**
   * A refusal with a reason.
   *
   * @param reason why the day could not be replayed
   * @param message what, in words
   */
  public ReplayException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  /**
   * Why the day could not be replayed.
   *
   * @return the reason
   */
  public Reason reason() {
    return reason;
  }
}

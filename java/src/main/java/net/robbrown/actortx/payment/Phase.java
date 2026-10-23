package net.robbrown.actortx.payment;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Where a transaction is in its life: a name, and whether it is over. Steps bring their own phases
 * (accepting, authorising...), so adding a step adds a phase without editing this class; only the
 * ones every transaction shares are constants here.
 *
 * @param wire the name used in JSON and the audit log
 * @param terminal has the transaction ended?
 */
public record Phase(String wire, boolean terminal) {

  /** Just arrived. */
  public static final Phase RECEIVED = new Phase("received", false);

  /** Asking for prices. */
  public static final Phase QUOTING = new Phase("quoting", false);

  /** Asking a provider what it holds. */
  public static final Phase RECONCILING = new Phase("reconciling", false);

  /** Undoing what was done. */
  public static final Phase COMPENSATING = new Phase("compensating", false);

  /** Paid and settled. */
  public static final Phase COMPLETED = new Phase("completed", true);

  /** Stopped cleanly. */
  public static final Phase FAILED = new Phase("failed", true);

  /** Waiting for a person. */
  public static final Phase NEEDS_REVIEW = new Phase("needs_review", true);

  /** A phase a step is in while it runs. */
  public static Phase running(String wire) {
    return new Phase(wire, false);
  }

  /** The phase a transaction is in once it has ended this way. */
  public static Phase ended(Outcome outcome) {
    return new Phase(outcome.wire(), true);
  }

  /** The name used in JSON and the audit log. */
  @JsonValue
  @Override
  public String wire() {
    return wire;
  }
}

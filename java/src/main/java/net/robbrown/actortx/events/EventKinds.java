package net.robbrown.actortx.events;

/**
 * The kinds of event the payment workflow announces: the vocabulary of the story and of the audit
 * log. They are strings on the wire (the log is a long-lived record that other tools read), and
 * this is the one place they are named, so a rename or an addition is visible in one file.
 *
 * <p>Every record the workflow writes to the log is described by one of these; the log's readers
 * (replay, review, metrics) match on the same names.
 */
public final class EventKinds {

  /**
   * The version of the data a {@link #TX_RECEIVED} record carries; bump it when its shape changes.
   */
  public static final int SCHEMA_VERSION = 1;

  /** The {@code cancel_by_key} event. */
  public static final String CANCEL_BY_KEY = "cancel_by_key";

  /** The {@code deadline_missed} event. */
  public static final String DEADLINE_MISSED = "deadline_missed";

  /** The {@code deadline_set} event. */
  public static final String DEADLINE_SET = "deadline_set";

  /** The {@code eligibility_decided} event. */
  public static final String ELIGIBILITY_DECIDED = "eligibility_decided";

  /** The {@code failover} event. */
  public static final String FAILOVER = "failover";

  /** The {@code in_doubt} event. */
  public static final String IN_DOUBT = "in_doubt";

  /** The {@code needs_review} event. */
  public static final String NEEDS_REVIEW = "needs_review";

  /** The {@code policy_resolved} event. */
  public static final String POLICY_RESOLVED = "policy_resolved";

  /** The {@code provider_chosen} event. */
  public static final String PROVIDER_CHOSEN = "provider_chosen";

  /** The {@code quote_expired} event. */
  public static final String QUOTE_EXPIRED = "quote_expired";

  /** The {@code reconcile_pending} event. */
  public static final String RECONCILE_PENDING = "reconcile_pending";

  /** The {@code reconciled} event. */
  public static final String RECONCILED = "reconciled";

  /** The {@code status_check} event. */
  public static final String STATUS_CHECK = "status_check";

  /** The {@code step_failed} event. */
  public static final String STEP_FAILED = "step_failed";

  /** The {@code step_retry} event. */
  public static final String STEP_RETRY = "step_retry";

  /** The {@code step_started} event. */
  public static final String STEP_STARTED = "step_started";

  /** The {@code step_succeeded} event. */
  public static final String STEP_SUCCEEDED = "step_succeeded";

  /** The {@code tx_completed} event. */
  public static final String TX_COMPLETED = "tx_completed";

  /** The {@code tx_failed} event. */
  public static final String TX_FAILED = "tx_failed";

  /** The {@code tx_received} event. */
  public static final String TX_RECEIVED = "tx_received";

  /** The {@code undo_retry} event. */
  public static final String UNDO_RETRY = "undo_retry";

  /** The {@code undo_started} event. */
  public static final String UNDO_STARTED = "undo_started";

  /** The {@code undone} event. */
  public static final String UNDONE = "undone";

  /** The {@code unsupported_step} event. */
  public static final String UNSUPPORTED_STEP = "unsupported_step";

  private EventKinds() {}
}

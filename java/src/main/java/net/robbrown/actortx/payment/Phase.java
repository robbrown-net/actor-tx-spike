package net.robbrown.actortx.payment;

/** Where a transaction is in its life. */
public enum Phase {
  RECEIVED,
  QUOTING,
  ACCEPTING,
  AUTHORISING,
  PAYING,
  SETTLING,
  RECONCILING,
  COMPENSATING,
  COMPLETED,
  FAILED,
  NEEDS_REVIEW;

  /** Has the transaction ended? */
  public boolean terminal() {
    return this == COMPLETED || this == FAILED || this == NEEDS_REVIEW;
  }

  /** The name used in JSON and the audit log. */
  public String wire() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }
}

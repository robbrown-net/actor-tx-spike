package net.robbrown.actortx.provider;

import java.util.Locale;

/** Everything a provider can be asked to do. */
public enum Operation {
  REQUEST_QUOTE(BulkheadClass.QUOTE),
  ACCEPT_QUOTE(BulkheadClass.STEP),
  AUTHORISE(BulkheadClass.STEP),
  PAY(BulkheadClass.STEP),
  SETTLE(BulkheadClass.STEP),
  CANCEL_ACCEPT(BulkheadClass.PROTECT),
  VOID_AUTHORISATION(BulkheadClass.PROTECT),
  REFUND(BulkheadClass.PROTECT),
  STATUS(BulkheadClass.PROTECT),
  PROBE(BulkheadClass.PROTECT);

  private final BulkheadClass bulkheadClass;

  Operation(BulkheadClass bulkheadClass) {
    this.bulkheadClass = bulkheadClass;
  }

  /** Which share of a provider's slots this operation may use. */
  public BulkheadClass bulkheadClass() {
    return bulkheadClass;
  }

  /** The name used in JSON and the audit log: {@code "accept_quote"}. */
  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }
}

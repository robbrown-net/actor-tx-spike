package net.robbrown.actortx.provider;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;

/**
 * Something a provider can be asked to do: a name, and which bulkhead class its calls count
 * against. It is a value, not a closed list: a new step (screening, hedging, funding) brings its
 * own operation, and nothing here needs to change. The constants are the operations the standard
 * payment uses.
 *
 * @param wire the name used in JSON, keys and the audit log
 * @param bulkheadClass which share of a provider's capacity its calls use
 */
public record Operation(String wire, BulkheadClass bulkheadClass) {

  /** Ask for a price. */
  public static final Operation REQUEST_QUOTE = new Operation("request_quote", BulkheadClass.QUOTE);

  /** Take the quoted price. */
  public static final Operation ACCEPT_QUOTE = new Operation("accept_quote", BulkheadClass.STEP);

  /** Authorise the payment. */
  public static final Operation AUTHORISE = new Operation("authorise", BulkheadClass.STEP);

  /** Make the payment. */
  public static final Operation PAY = new Operation("pay", BulkheadClass.STEP);

  /** Settle the payment. */
  public static final Operation SETTLE = new Operation("settle", BulkheadClass.STEP);

  /** Undo an accepted quote. */
  public static final Operation CANCEL_ACCEPT =
      new Operation("cancel_accept", BulkheadClass.PROTECT);

  /** Undo an authorisation. */
  public static final Operation VOID_AUTHORISATION =
      new Operation("void_authorisation", BulkheadClass.PROTECT);

  /** Undo a payment. */
  public static final Operation REFUND = new Operation("refund", BulkheadClass.PROTECT);

  /** Ask what the provider holds for a key. */
  public static final Operation STATUS = new Operation("status", BulkheadClass.PROTECT);

  /** Ask whether the provider is well. */
  public static final Operation PROBE = new Operation("probe", BulkheadClass.PROTECT);

  /** The operations the standard payment uses, in no particular order. */
  public static final List<Operation> STANDARD =
      List.of(
          REQUEST_QUOTE,
          ACCEPT_QUOTE,
          AUTHORISE,
          PAY,
          SETTLE,
          CANCEL_ACCEPT,
          VOID_AUTHORISATION,
          REFUND,
          STATUS,
          PROBE);

  /** The name used in JSON, keys and the audit log. */
  @JsonValue
  @Override
  public String wire() {
    return wire;
  }
}

package net.robbrown.actortx.payment;

import java.util.Optional;
import net.robbrown.actortx.provider.Operation;

/** The steps of the standard payment, and a way to declare more. */
public final class Steps {

  /** A step declared as plain data. */
  public record Declared(
      Operation operation,
      String keyName,
      String verb,
      Phase phase,
      String label,
      Optional<Compensation> compensation,
      boolean confirmedByStatus)
      implements Step {}

  /** Take the quoted price; undone by cancelling it. */
  public static final Step ACCEPT_QUOTE =
      new Declared(
          Operation.ACCEPT_QUOTE,
          "accept",
          "accept the quote",
          Phase.running("accepting"),
          "accept",
          Optional.of(new Compensation(Operation.CANCEL_ACCEPT, "cancel the accepted quote")),
          false);

  /** Authorise the payment; undone by voiding it. */
  public static final Step AUTHORISE =
      new Declared(
          Operation.AUTHORISE,
          "authorise",
          "authorise the payment",
          Phase.running("authorising"),
          "authorise",
          Optional.of(new Compensation(Operation.VOID_AUTHORISATION, "void the authorisation")),
          false);

  /** Make the payment: the point of no return. */
  public static final Step PAY =
      new Declared(
          Operation.PAY,
          "pay",
          "make the payment",
          Phase.running("paying"),
          "pay",
          Optional.empty(),
          false);

  /** Settle the payment. */
  public static final Step SETTLE =
      new Declared(
          Operation.SETTLE,
          "settle",
          "settle the payment",
          Phase.running("settling"),
          "settlement",
          Optional.empty(),
          false);

  private Steps() {}

  /** A reversible step of your own. */
  public static Step reversible(
      Operation operation, String keyName, String verb, Compensation compensation) {
    return new Declared(
        operation,
        keyName,
        verb,
        Phase.running(keyName + "ing"),
        keyName,
        Optional.of(compensation),
        false);
  }
}

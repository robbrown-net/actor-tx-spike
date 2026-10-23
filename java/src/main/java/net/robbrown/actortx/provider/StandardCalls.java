package net.robbrown.actortx.provider;

import java.util.Map;

/** Routes a generic {@code perform} to the provider's typed methods for the standard operations. */
final class StandardCalls {

  /** One typed call. */
  @FunctionalInterface
  private interface Call {
    Answer<Receipt> on(ProviderAdapter adapter, Quote quote, String key);
  }

  private static final Map<Operation, Call> CALLS =
      Map.of(
          Operation.ACCEPT_QUOTE, (a, q, k) -> a.acceptQuote(q, k),
          Operation.AUTHORISE, (a, q, k) -> a.authorise(q, k),
          Operation.PAY, (a, q, k) -> a.pay(q, k),
          Operation.SETTLE, (a, q, k) -> a.settle(k),
          Operation.CANCEL_ACCEPT, (a, q, k) -> a.cancelAccept(k),
          Operation.VOID_AUTHORISATION, (a, q, k) -> a.voidAuthorisation(k),
          Operation.REFUND, (a, q, k) -> a.refund(k));

  private StandardCalls() {}

  static Answer<Receipt> call(ProviderAdapter adapter, Operation op, Quote quote, String key) {
    return CALLS
        .getOrDefault(op, (a, q, k) -> new Answer.Error<>("unsupported_operation: " + op.wire()))
        .on(adapter, quote, key);
  }
}

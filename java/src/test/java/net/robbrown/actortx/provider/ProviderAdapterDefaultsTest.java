package net.robbrown.actortx.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The generic entry points route to the typed methods for the standard operations. */
class ProviderAdapterDefaultsTest {

  private final List<String> calls = new ArrayList<>();

  private final ProviderAdapter adapter =
      new ProviderAdapter() {
        private Answer<Receipt> note(String what) {
          calls.add(what);
          return new Answer.Ok<>(null);
        }

        @Override
        public Answer<Quote> requestQuote(QuoteRequest request) {
          return new Answer.Timeout<>();
        }

        @Override
        public Answer<Receipt> acceptQuote(Quote quote, String key) {
          return note("accept " + key);
        }

        @Override
        public Answer<Receipt> authorise(Quote quote, String key) {
          return note("authorise " + key);
        }

        @Override
        public Answer<Receipt> pay(Quote quote, String key) {
          return note("pay " + key);
        }

        @Override
        public Answer<Receipt> settle(String key) {
          return note("settle " + key);
        }

        @Override
        public Answer<Receipt> cancelAccept(String key) {
          return note("cancel " + key);
        }

        @Override
        public Answer<Receipt> voidAuthorisation(String key) {
          return note("void " + key);
        }

        @Override
        public Answer<Receipt> refund(String key) {
          return note("refund " + key);
        }

        @Override
        public Answer<Receipt> status(Operation op, String key) {
          return new Answer.NotFound<>();
        }

        @Override
        public Answer<ProbeInfo> probe() {
          return new Answer.Timeout<>();
        }
      };

  @Test
  void everyStandardOperationReachesItsTypedMethod() {
    for (Operation op :
        List.of(
            Operation.ACCEPT_QUOTE,
            Operation.AUTHORISE,
            Operation.PAY,
            Operation.SETTLE,
            Operation.CANCEL_ACCEPT,
            Operation.VOID_AUTHORISATION,
            Operation.REFUND)) {
      assertThat(adapter.perform(op, null, "k").isOk()).isTrue();
      assertThat(adapter.supports(op)).isTrue();
    }
    assertThat(calls)
        .containsExactly(
            "accept k", "authorise k", "pay k", "settle k", "cancel k", "void k", "refund k");
  }

  @Test
  void anOperationItDoesNotKnowIsRefusedPlainly() {
    Operation screening = new Operation("screen", BulkheadClass.STEP);

    assertThat(adapter.supports(screening)).isFalse();
    assertThat(adapter.perform(screening, null, "k"))
        .isEqualTo(new Answer.Error<>("unsupported_operation: screen"));
  }
}

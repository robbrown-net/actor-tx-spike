package net.robbrown.actortx.service;

import static org.assertj.core.api.Assertions.assertThat;

import net.robbrown.actortx.provider.Answer;
import net.robbrown.actortx.provider.FakeProvider;
import net.robbrown.actortx.provider.Operation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ServerReviewProvidersTest {

  private final FakeProvider fake = new FakeProvider("p");
  private final StubCatalog catalog = new StubCatalog(fake);
  private final ServerReviewProviders providers = new ServerReviewProviders(catalog);

  @AfterEach
  void tearDown() {
    catalog.close();
  }

  @Test
  void onlyKnownProvidersAreAvailable() {
    assertThat(providers.available("p")).isTrue();
    assertThat(providers.available("q")).isFalse();
  }

  @Test
  void statusAsksTheProviderWhatItHolds() {
    assertThat(providers.status("p", Operation.PAY, "tx:p:pay"))
        .isInstanceOf(Answer.NotFound.class);
  }

  @Test
  void stepsThatNeedNoQuoteCanBeDrivenAgain() {
    for (Operation op :
        new Operation[] {
          Operation.SETTLE, Operation.CANCEL_ACCEPT, Operation.VOID_AUTHORISATION, Operation.REFUND
        }) {
      assertThat(providers.invoke("p", op, "k:" + op).isOk()).isTrue();
    }
    assertThat(fake.effectCount(Operation.SETTLE)).isEqualTo(1);
  }

  @Test
  void stepsThatNeedTheQuoteAreRefused() {
    assertThat(providers.invoke("p", Operation.PAY, "k"))
        .isEqualTo(new Answer.Error<>("not_retriable_without_the_quote"));
  }
}

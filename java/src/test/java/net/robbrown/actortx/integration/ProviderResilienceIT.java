package net.robbrown.actortx.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import net.robbrown.actortx.provider.Answer;
import net.robbrown.actortx.provider.Answer.Error;
import net.robbrown.actortx.provider.Answer.InDoubt;
import net.robbrown.actortx.provider.Answer.Ok;
import net.robbrown.actortx.provider.Answer.Timeout;
import net.robbrown.actortx.provider.FakeProvider;
import net.robbrown.actortx.provider.HealthConfig;
import net.robbrown.actortx.provider.HealthState;
import net.robbrown.actortx.provider.Limits;
import net.robbrown.actortx.provider.Mode;
import net.robbrown.actortx.provider.Operation;
import net.robbrown.actortx.provider.ProviderServer;
import net.robbrown.actortx.provider.Quote;
import net.robbrown.actortx.provider.QuoteRequest;
import org.junit.jupiter.api.Test;

/**
 * Provider servers over real fakes with real (short) cool-downs: breaker, drift, doubt,
 * idempotency.
 */
class ProviderResilienceIT {

  private static final QuoteRequest REQUEST = QuoteRequest.of("r", 10_000L);
  private static final Duration SECOND = Duration.ofSeconds(1);

  private static ProviderServer server(FakeProvider fake, HealthConfig health) {
    return new ProviderServer(
        fake.id(),
        fake,
        health,
        Limits.defaults(),
        (tx, actor, kind, message, data) -> {},
        System::currentTimeMillis);
  }

  @Test
  void aStalledProviderIsIsolatedByItsBreakerWhileOthersKeepServing() throws InterruptedException {
    HealthConfig health =
        HealthConfig.defaults().withFailureThreshold(3).withCoolDownMs(300).withSlowMs(40);
    FakeProvider good = new FakeProvider("good").rate("1.2");
    FakeProvider flaky =
        new FakeProvider("flaky").rate("1.3").script(Operation.REQUEST_QUOTE, Mode.TIMEOUT);
    try (ProviderServer goodServer = server(good, health);
        ProviderServer flakyServer = server(flaky, health)) {
      for (int i = 0; i < 3; i++) {
        assertThat(flakyServer.requestQuote(REQUEST, Duration.ofMillis(200)))
            .isInstanceOf(Timeout.class);
      }
      assertThat(flakyServer.state()).isEqualTo(HealthState.STALLED);

      assertThat(flakyServer.requestQuote(REQUEST, SECOND))
          .isEqualTo(new Error<Quote>("circuit_open"));
      assertThat(goodServer.requestQuote(REQUEST, SECOND)).isInstanceOf(Ok.class);
      assertThat(goodServer.state()).isEqualTo(HealthState.HEALTHY);

      // flaky recovers: after the cool-down a single probe call closes the circuit
      flaky.script(Operation.REQUEST_QUOTE, Mode.OK);
      Thread.sleep(350);
      assertThat(flakyServer.requestQuote(REQUEST, SECOND)).isInstanceOf(Ok.class);
      assertThat(flakyServer.state()).isEqualTo(HealthState.DEGRADED);

      // ...and earns healthy back gradually
      flakyServer.requestQuote(REQUEST, SECOND);
      flakyServer.requestQuote(REQUEST, SECOND);
      assertThat(flakyServer.state()).isEqualTo(HealthState.HEALTHY);
    }
  }

  @Test
  void slowResponsesDegradeAProviderWithoutStallingIt() {
    FakeProvider slow = new FakeProvider("slow").latencyMs(30);
    try (ProviderServer server = server(slow, HealthConfig.defaults().withSlowMs(20))) {
      assertThat(server.requestQuote(REQUEST, SECOND)).isInstanceOf(Ok.class);

      assertThat(server.state()).isEqualTo(HealthState.DEGRADED);
    }
  }

  @Test
  void aSilentVersionChangeQuarantinesAProviderUntilReviewed() {
    FakeProvider p = new FakeProvider("p");
    try (ProviderServer server = server(p, HealthConfig.defaults())) {
      server.probe(SECOND);
      p.version("2");
      server.probe(SECOND);

      assertThat(server.state()).isEqualTo(HealthState.QUARANTINED);
      assertThat(server.requestQuote(REQUEST, SECOND)).isEqualTo(new Error<Quote>("quarantined"));
      server.release();
      assertThat(server.requestQuote(REQUEST, SECOND)).isInstanceOf(Ok.class);
    }
  }

  @Test
  void anInDoubtPaymentIsResolvedByStatusLookupAndNeverDoubleExecuted() {
    FakeProvider p = new FakeProvider("p");
    try (ProviderServer server = server(p, HealthConfig.defaults())) {
      Quote quote = ((Ok<Quote>) server.requestQuote(REQUEST, SECOND)).value();
      p.script(Operation.PAY, Mode.IN_DOUBT);

      assertThat(server.invoke(Operation.PAY, a -> a.pay(quote, "pay-1"), SECOND))
          .isEqualTo(new InDoubt<>());

      // reconcile before trying anything else
      Answer<?> status =
          server.invoke(Operation.STATUS, a -> a.status(Operation.PAY, "pay-1"), SECOND);
      assertThat(status).isInstanceOf(Ok.class);

      // a blind retry with the same idempotency key must not pay twice
      assertThat(server.invoke(Operation.PAY, a -> a.pay(quote, "pay-1"), SECOND))
          .isEqualTo(new InDoubt<>());
      assertThat(p.effectCount(Operation.PAY)).isEqualTo(1);
    }
  }

  @Test
  void theWholeLifecycleAndItsUndosRunThroughTheServerIdempotently() {
    FakeProvider p = new FakeProvider("p");
    try (ProviderServer server = server(p, HealthConfig.defaults())) {
      Quote quote = ((Ok<Quote>) server.requestQuote(REQUEST, SECOND)).value();

      assertThat(
              server.invoke(
                  Operation.ACCEPT_QUOTE, a -> a.acceptQuote(quote, "t:p:accept"), SECOND))
          .isInstanceOf(Ok.class);
      assertThat(
              server.invoke(Operation.AUTHORISE, a -> a.authorise(quote, "t:p:authorise"), SECOND))
          .isInstanceOf(Ok.class);
      assertThat(server.invoke(Operation.PAY, a -> a.pay(quote, "t:p:pay"), SECOND))
          .isInstanceOf(Ok.class);
      assertThat(server.invoke(Operation.SETTLE, a -> a.settle("t:p:settle"), SECOND))
          .isInstanceOf(Ok.class);
      assertThat(server.invoke(Operation.REFUND, a -> a.refund("t:p:pay"), SECOND))
          .isInstanceOf(Ok.class);
      assertThat(
              server.invoke(
                  Operation.VOID_AUTHORISATION, a -> a.voidAuthorisation("t:p:authorise"), SECOND))
          .isInstanceOf(Ok.class);
      assertThat(server.invoke(Operation.CANCEL_ACCEPT, a -> a.cancelAccept("t:p:accept"), SECOND))
          .isInstanceOf(Ok.class);

      assertThat(server.invoke(Operation.PAY, a -> a.pay(quote, "t:p:pay"), SECOND))
          .isInstanceOf(Ok.class);
      assertThat(p.effectCount(Operation.PAY)).isEqualTo(1);
      assertThat(p.effectCount(Operation.REFUND)).isEqualTo(1);
      assertThat(p.effects()).hasSize(7);
    }
  }
}

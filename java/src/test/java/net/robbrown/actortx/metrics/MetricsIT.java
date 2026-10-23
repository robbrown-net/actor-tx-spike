package net.robbrown.actortx.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import net.robbrown.actortx.eligibility.Eligibility;
import net.robbrown.actortx.events.EventBus;
import net.robbrown.actortx.events.Subscription;
import net.robbrown.actortx.narrative.Narrative;
import net.robbrown.actortx.payment.Payment;
import net.robbrown.actortx.payment.PaymentOptions;
import net.robbrown.actortx.payment.PaymentServices;
import net.robbrown.actortx.payment.Result;
import net.robbrown.actortx.payment.Result.Completed;
import net.robbrown.actortx.payment.TransactionRequest;
import net.robbrown.actortx.policy.Policy;
import net.robbrown.actortx.provider.FakeProvider;
import net.robbrown.actortx.provider.Mode;
import net.robbrown.actortx.provider.Operation;
import net.robbrown.actortx.provider.ProviderServer;
import net.robbrown.actortx.selection.Participant;
import net.robbrown.actortx.selection.QuoteSelector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Metrics folding a real payment run off the real event bus, and the narrative of the same run. */
class MetricsIT {

  private EventBus bus;
  private QuoteSelector selector;
  private FakeProvider north;
  private FakeProvider cobalt;
  private ProviderServer northServer;
  private ProviderServer cobaltServer;
  private PaymentServices services;

  @BeforeEach
  void setUp() {
    bus = new EventBus();
    selector = new QuoteSelector(bus);
    north = new FakeProvider("northwind", bus).rate("1.10");
    cobalt = new FakeProvider("cobalt", bus).rate("1.00");
    northServer = new ProviderServer("northwind", north);
    cobaltServer = new ProviderServer("cobalt", cobalt);
    services = PaymentServices.of(selector, bus).withBarrier(bus::barrier);
  }

  @AfterEach
  void tearDown() {
    northServer.close();
    cobaltServer.close();
    selector.close();
    bus.close();
  }

  private Result pay(String id) {
    Payment.Routing routing =
        new Payment.Routing(
            List.of(
                Participant.of("northwind", northServer), Participant.of("cobalt", cobaltServer)),
            Eligibility.open(),
            Policy.defaults(),
            "global");
    PaymentOptions options =
        PaymentOptions.defaults()
            .withStepTimeoutMs(300)
            .withBackoffMs(1)
            .withReconcileIntervalMs(1);
    return new Payment(TransactionRequest.of(id, 100_000), routing, options, services)
        .start()
        .await(Duration.ofSeconds(10))
        .orElseThrow();
  }

  @Test
  void aRealPaymentIsCountedAndNarrated() {
    Metrics metrics = new Metrics();
    Subscription subscription = bus.subscribe(metrics::onEvent);
    try {
      metrics.sample();
      north.script(Operation.AUTHORISE, Mode.REJECT);

      Result result = pay("tx-metrics");
      assertThat(result).isInstanceOf(Completed.class);
      assertThat(((Completed) result).summary().provider()).isEqualTo("cobalt");

      bus.barrier();
      Sample sample = metrics.sample();

      assertThat(sample.tx()).isEqualTo(new TxCounts(1, 1, 0, 0, 1, 0));
      assertThat(sample.e2e().count()).isEqualTo(1);
      assertThat(metrics.snapshot().latest()).isEqualTo(sample);
      assertThat(metrics.snapshot().minutes()).isEmpty();
    } finally {
      subscription.close();
    }

    List<String> lines = bus.history().stream().map(e -> Narrative.format(e)).toList();
    assertThat(lines).isNotEmpty();
    assertThat(lines).allMatch(line -> line.matches("\\d{2}:\\d{2}:\\d{2}\\.\\d{3} .*"));
    assertThat(lines).anyMatch(line -> line.contains("transaction received"));
  }
}

package net.robbrown.actortx.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.robbrown.actortx.audit.AuditRouter;
import net.robbrown.actortx.eligibility.Eligibility;
import net.robbrown.actortx.events.DurableSink;
import net.robbrown.actortx.events.Event;
import net.robbrown.actortx.events.EventBus;
import net.robbrown.actortx.payment.Payment.Routing;
import net.robbrown.actortx.payment.Result.Completed;
import net.robbrown.actortx.payment.Result.Failed;
import net.robbrown.actortx.payment.Result.NeedsReview;
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
import org.junit.jupiter.api.io.TempDir;

/**
 * Payments over the real event bus and a real hash-chained audit log: every step is written down
 * before it is taken, and when the log fails no money moves.
 */
class PaymentResilienceIT {

  @TempDir Path dir;

  private EventBus bus;
  private AuditRouter audit;
  private QuoteSelector selector;
  private FakeProvider north;
  private FakeProvider cobalt;
  private ProviderServer northServer;
  private ProviderServer cobaltServer;
  private PaymentServices services;

  @BeforeEach
  void setUp() {
    bus = new EventBus();
    audit = AuditRouter.open(dir, List.of("IN"), true);
    bus.registerSink("audit", audit);
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
    audit.close();
  }

  private Result pay(String id) {
    Routing routing =
        new Routing(
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

  private List<String> log() throws IOException {
    audit.flush();
    try (Stream<Path> files = Files.list(dir)) {
      return files
          .filter(f -> f.toString().endsWith(".log"))
          .flatMap(
              f -> {
                try {
                  return Files.readAllLines(f).stream();
                } catch (IOException unreadable) {
                  throw new IllegalStateException(unreadable);
                }
              })
          .toList();
    }
  }

  @Test
  void aCompletedPaymentIsFullyOnTheRecord() throws IOException {
    assertThat(pay("tx-a")).isInstanceOf(Completed.class);

    assertThat(log())
        .anyMatch(l -> l.contains("\"kind\":\"step_succeeded\""))
        .anyMatch(l -> l.contains("\"kind\":\"tx_completed\""));
  }

  @Test
  void failoverAfterARejectionIsRecorded() throws IOException {
    north.script(Operation.AUTHORISE, Mode.REJECT);

    Result result = pay("tx-b");

    assertThat(((Completed) result).summary().provider()).isEqualTo("cobalt");
    assertThat(log()).anyMatch(l -> l.contains("\"kind\":\"failover\""));
  }

  @Test
  void aLostAnswerIsReconciledAgainstTheProvider() {
    north.script(Operation.PAY, Mode.IN_DOUBT);

    assertThat(pay("tx-c")).isInstanceOf(Completed.class);
    assertThat(north.calls().get(Operation.PAY)).isEqualTo(1);
  }

  @Test
  void anUnresolvablePaymentGoesToReviewNotToAnotherProvider() {
    north.script(Operation.PAY, Mode.TIMEOUT);

    assertThat(pay("tx-d")).isEqualTo(new NeedsReview("in_doubt: pay"));
    assertThat(cobalt.calls()).doesNotContainKey(Operation.PAY);
  }

  @Test
  void aTimedOutAcceptIsCancelledByKey() {
    north.script(Operation.ACCEPT_QUOTE, Mode.TIMEOUT);

    assertThat(pay("tx-e")).isInstanceOf(Completed.class);
  }

  @Test
  void aFailedSettlementGoesToReview() {
    north.script(Operation.SETTLE, Mode.REJECT);

    assertThat(pay("tx-f")).isInstanceOf(NeedsReview.class);
  }

  @Test
  void aCompensationThatCannotBeDoneGoesToReview() {
    north.script(Operation.AUTHORISE, Mode.REJECT);
    north.script(Operation.CANCEL_ACCEPT, Mode.REJECT);

    assertThat(pay("tx-g")).isInstanceOf(NeedsReview.class);
  }

  @Test
  void aBusyProviderIsWaitedForNotFailed() {
    north.script(Operation.ACCEPT_QUOTE, Mode.error("overloaded"), Mode.OK);

    assertThat(((Completed) pay("tx-h")).summary().provider()).isEqualTo("northwind");
  }

  @Test
  void whenTheLogCannotBeWrittenNoMoneyMoves() {
    bus.registerSink(
        "broken",
        new DurableSink() {
          @Override
          public void write(Event event) {}

          @Override
          public Optional<String> flush() {
            return Optional.of("disk_full");
          }
        });

    Result result = pay("tx-i");

    assertThat(result).isInstanceOf(Failed.class);
    assertThat(((Failed) result).reason()).startsWith("audit_unavailable");
    assertThat(north.calls()).doesNotContainKey(Operation.ACCEPT_QUOTE);
  }
}

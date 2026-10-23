package net.robbrown.actortx.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.robbrown.actortx.eligibility.Eligibility;
import net.robbrown.actortx.events.Event;
import net.robbrown.actortx.events.EventBus;
import net.robbrown.actortx.events.Subscription;
import net.robbrown.actortx.payment.Payment;
import net.robbrown.actortx.payment.Payment.Routing;
import net.robbrown.actortx.payment.PaymentOptions;
import net.robbrown.actortx.payment.PaymentServices;
import net.robbrown.actortx.payment.Result;
import net.robbrown.actortx.payment.Result.NeedsReview;
import net.robbrown.actortx.payment.TransactionRequest;
import net.robbrown.actortx.policy.Policy;
import net.robbrown.actortx.provider.Answer;
import net.robbrown.actortx.provider.FakeProvider;
import net.robbrown.actortx.provider.Mode;
import net.robbrown.actortx.provider.Operation;
import net.robbrown.actortx.provider.ProviderAdapter;
import net.robbrown.actortx.provider.ProviderServer;
import net.robbrown.actortx.provider.Receipt;
import net.robbrown.actortx.review.ReviewEntry.Status;
import net.robbrown.actortx.selection.Participant;
import net.robbrown.actortx.selection.QuoteSelector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The review workflow over the real stack: a payment that really ends in {@code needs_review} (the
 * provider refuses the settlement), a real provider server and fake provider, the real event bus.
 * Every step is synchronised with {@link EventBus#barrier()}, so nothing waits on a sleep.
 */
class ReviewWorkflowIT {

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  private EventBus bus;
  private QuoteSelector selector;
  private FakeProvider north;
  private ProviderServer northServer;
  private final Map<String, Payment> started = new ConcurrentHashMap<>();
  private ReviewQueue queue;
  private ReviewService service;
  private Subscription subscription;

  @BeforeEach
  void setUp() {
    bus = new EventBus();
    selector = new QuoteSelector(bus);
    north = new FakeProvider("northwind", bus).rate("1.10");
    northServer = new ProviderServer("northwind", north);
    queue = new ReviewQueue(id -> Optional.ofNullable(started.get(id)).map(Payment::snapshot));
    subscription = bus.subscribe(queue::onEvent);
    service =
        new ReviewService(
            queue,
            new ServerProviders(Map.of("northwind", northServer)),
            bus,
            bus::barrier,
            System::currentTimeMillis);
  }

  @AfterEach
  void tearDown() {
    subscription.close();
    northServer.close();
    selector.close();
    bus.close();
  }

  /** A payment whose settlement the provider refuses: paid, not settled, escalated. */
  private String paymentNeedingReview(String id) {
    Routing routing =
        new Routing(
            List.of(Participant.of("northwind", northServer)),
            Eligibility.open(),
            Policy.defaults(),
            "global");
    PaymentOptions options =
        PaymentOptions.defaults()
            .withStepTimeoutMs(300)
            .withBackoffMs(1)
            .withReconcileIntervalMs(1);
    PaymentServices services = PaymentServices.of(selector, bus).withBarrier(bus::barrier);
    Payment payment = new Payment(TransactionRequest.of(id, 100_000), routing, options, services);
    started.put(id, payment);
    Result result = payment.start().await(Duration.ofSeconds(10)).orElseThrow();
    assertThat(result).isInstanceOf(NeedsReview.class);
    return id;
  }

  @Test
  void aStuckPaymentIsReviewedCheckedRetriedAndClosedOnTheRecord() {
    north.script(Operation.SETTLE, Mode.REJECT);
    String tx = paymentNeedingReview("rv-it-1");
    bus.barrier();

    ReviewEntry opened = service.view(tx).orElseThrow();
    assertThat(opened.open()).isTrue();
    assertThat(opened.provider()).isEqualTo("northwind");
    assertThat(opened.done()).containsExactly("accept_quote", "authorise", "pay");

    assertThat(service.check(tx, "robin")).hasSize(4);
    north.resetScripts();
    RetryResult retried = service.retry(tx, "settle", "robin");
    assertThat(retried.result()).isEqualTo("ok");
    service.retry(tx, "settle", "robin");
    assertThat(north.effectCount(Operation.SETTLE)).isEqualTo(1);

    service.resolve(tx, "paid", "robin", "settled by hand after the statement");
    bus.barrier();

    assertThat(service.view(tx).orElseThrow().status()).isEqualTo(Status.RESOLVED);
    assertThat(reviewKinds(tx))
        .containsExactly(
            "review_checked",
            "review_retry",
            "review_result",
            "review_retry",
            "review_result",
            "review_resolved");
  }

  @Test
  void aQueueRebuiltFromTheRecordShowsTheSameReview() {
    north.script(Operation.SETTLE, Mode.REJECT);
    String tx = paymentNeedingReview("rv-it-2");
    bus.barrier();
    service.check(tx, "robin");
    north.resetScripts();
    service.retry(tx, "settle", "robin");
    service.resolve(tx, "paid", "robin", "confirmed");
    bus.barrier();

    ReviewQueue rebuilt = new ReviewQueue();
    rebuilt.load(bus.history());

    ReviewEntry again = rebuilt.find(tx).orElseThrow();
    assertThat(again.status()).isEqualTo(Status.RESOLVED);
    assertThat(again.resolution().outcome()).isEqualTo("paid");
    assertThat(again.actions())
        .extracting(ReviewEntry.ReviewAction::kind)
        .containsExactly("check", "retry", "result");
  }

  private List<String> reviewKinds(String tx) {
    return bus.history().stream()
        .filter(e -> tx.equals(e.txId()) && "review".equals(e.actor()))
        .map(Event::kind)
        .toList();
  }

  /** The host's wiring: each provider's server carries the review's calls. */
  private record ServerProviders(Map<String, ProviderServer> servers) implements ReviewProviders {

    @Override
    public boolean available(String provider) {
      return servers.containsKey(provider);
    }

    @Override
    public Answer<Receipt> status(String provider, Operation op, String key) {
      return servers
          .get(provider)
          .invoke(Operation.STATUS, adapter -> adapter.status(op, key), TIMEOUT);
    }

    @Override
    public Answer<Receipt> invoke(String provider, Operation op, String key) {
      return servers.get(provider).invoke(op, adapter -> send(adapter, op, key), TIMEOUT);
    }

    private static Answer<Receipt> send(ProviderAdapter adapter, Operation op, String key) {
      return switch (op) {
        case SETTLE -> adapter.settle(key);
        case REFUND -> adapter.refund(key);
        case CANCEL_ACCEPT -> adapter.cancelAccept(key);
        default -> adapter.voidAuthorisation(key);
      };
    }
  }
}

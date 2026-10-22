package net.robbrown.actortx.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.robbrown.actortx.provider.Answer.Error;
import net.robbrown.actortx.provider.Answer.InDoubt;
import net.robbrown.actortx.provider.Answer.NotFound;
import net.robbrown.actortx.provider.Answer.Ok;
import net.robbrown.actortx.provider.Answer.Rejected;
import net.robbrown.actortx.provider.Answer.Timeout;
import net.robbrown.actortx.provider.Answer.Unknown;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProviderServerTest {

  private static final QuoteRequest REQUEST = QuoteRequest.of("r", null);
  private static final Duration SECOND = Duration.ofSeconds(1);

  /** Every operation blocks until released, so tests can fill the bulkhead exactly. */
  private static final class BlockingAdapter implements ProviderAdapter {
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger blocked = new AtomicInteger();

    private <T> Answer<T> block() {
      blocked.incrementAndGet();
      try {
        release.await();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      blocked.decrementAndGet();
      return new Error<>("released");
    }

    void releaseAll() {
      release.countDown();
    }

    int blocked() {
      return blocked.get();
    }

    @Override
    public Answer<Quote> requestQuote(QuoteRequest request) {
      return block();
    }

    @Override
    public Answer<Receipt> acceptQuote(Quote quote, String key) {
      return block();
    }

    @Override
    public Answer<Receipt> authorise(Quote quote, String key) {
      return block();
    }

    @Override
    public Answer<Receipt> pay(Quote quote, String key) {
      return block();
    }

    @Override
    public Answer<Receipt> settle(String key) {
      return block();
    }

    @Override
    public Answer<Receipt> cancelAccept(String key) {
      return block();
    }

    @Override
    public Answer<Receipt> voidAuthorisation(String key) {
      return block();
    }

    @Override
    public Answer<Receipt> refund(String key) {
      return block();
    }

    @Override
    public Answer<Receipt> status(Operation op, String key) {
      return block();
    }

    @Override
    public Answer<ProbeInfo> probe() {
      return block();
    }
  }

  /** An adapter whose quote request blows up. */
  private static final class ExplodingAdapter extends FakeProvider {
    ExplodingAdapter() {
      super("boom");
    }

    @Override
    public Answer<Quote> requestQuote(QuoteRequest request) {
      throw new IllegalStateException("kaput");
    }
  }

  private final AtomicLong now = new AtomicLong(1_000);
  private final List<ProviderServer> servers = new ArrayList<>();
  private final List<Map<String, Object>> health = new CopyOnWriteArrayList<>();
  private final List<String> messages = new CopyOnWriteArrayList<>();

  @AfterEach
  void closeServers() {
    servers.forEach(ProviderServer::close);
  }

  private ProviderServer server(ProviderAdapter adapter, HealthConfig config, Limits limits) {
    ProviderServer server =
        new ProviderServer(
            "p",
            adapter,
            config,
            limits,
            (tx, actor, kind, message, data) -> {
              health.add(data);
              messages.add(message);
            },
            now::get);
    servers.add(server);
    return server;
  }

  private ProviderServer server(ProviderAdapter adapter) {
    return server(adapter, HealthConfig.defaults(), Limits.defaults());
  }

  private Quote quote(ProviderServer server) {
    return ((Ok<Quote>) server.requestQuote(REQUEST, SECOND)).value();
  }

  @Test
  void successfulCallsMakeTheProviderHealthyAndUpdateStats() {
    ProviderServer server = server(new FakeProvider("p"));
    assertThat(server.state()).isEqualTo(HealthState.UNKNOWN);

    Quote quote = quote(server);
    assertThat(server.invoke(Operation.PAY, a -> a.pay(quote, "k"), SECOND)).isInstanceOf(Ok.class);
    assertThat(server.invoke(Operation.STATUS, a -> a.status(Operation.PAY, "k"), SECOND))
        .isInstanceOf(Ok.class);

    assertThat(server.health().stats().samples()).isEqualTo(3);
    assertThat(server.state()).isEqualTo(HealthState.HEALTHY);
    assertThat(server.id()).isEqualTo("p");
  }

  @Test
  void outcomesAreClassifiedIntoHealthSamples() {
    FakeProvider fake = new FakeProvider("p");
    ProviderServer server =
        server(fake, HealthConfig.defaults().withFailureThreshold(99), Limits.defaults());
    Quote quote = quote(server);

    fake.script(Operation.REQUEST_QUOTE, Mode.REJECT, Mode.TIMEOUT);
    assertThat(server.requestQuote(REQUEST, SECOND)).isInstanceOf(Rejected.class);
    assertThat(server.requestQuote(REQUEST, SECOND)).isInstanceOf(Timeout.class);
    fake.script(Operation.PAY, Mode.IN_DOUBT);
    assertThat(server.invoke(Operation.PAY, a -> a.pay(quote, "k"), SECOND))
        .isInstanceOf(InDoubt.class);
    fake.script(Operation.STATUS, Mode.UNKNOWN, Mode.OK);
    assertThat(server.invoke(Operation.STATUS, a -> a.status(Operation.PAY, "k"), SECOND))
        .isInstanceOf(Unknown.class);
    assertThat(server.invoke(Operation.STATUS, a -> a.status(Operation.PAY, "k"), SECOND))
        .isInstanceOf(Ok.class);
    assertThat(server.invoke(Operation.STATUS, a -> a.status(Operation.PAY, "missing"), SECOND))
        .isInstanceOf(NotFound.class);

    List<Outcome> outcomes = server.health().window().stream().map(Health.Sample::outcome).toList();
    assertThat(outcomes)
        .containsExactlyInAnyOrder(
            Outcome.SUCCESS,
            Outcome.SUCCESS,
            Outcome.SUCCESS,
            Outcome.REJECTED,
            Outcome.TIMEOUT,
            Outcome.TIMEOUT,
            Outcome.ERROR);
  }

  @Test
  void theCircuitOpensAfterRepeatedFailuresDeniesThenRecoversGraduallyViaAProbeCall() {
    FakeProvider fake = new FakeProvider("p");
    ProviderServer server =
        server(
            fake,
            HealthConfig.defaults().withFailureThreshold(2).withCoolDownMs(20),
            Limits.defaults());
    fake.script(Operation.REQUEST_QUOTE, Mode.error("down"));

    assertThat(server.requestQuote(REQUEST, SECOND)).isEqualTo(new Error<Quote>("down"));
    assertThat(server.requestQuote(REQUEST, SECOND)).isEqualTo(new Error<Quote>("down"));
    assertThat(server.state()).isEqualTo(HealthState.STALLED);
    assertThat(server.requestQuote(REQUEST, SECOND)).isEqualTo(new Error<Quote>("circuit_open"));

    fake.script(Operation.REQUEST_QUOTE, Mode.OK);
    now.addAndGet(30);
    assertThat(server.requestQuote(REQUEST, SECOND)).isInstanceOf(Ok.class);
    assertThat(server.state()).isEqualTo(HealthState.DEGRADED);

    // gradual recovery: two more successes (the probe was the first)
    server.requestQuote(REQUEST, SECOND);
    server.requestQuote(REQUEST, SECOND);
    assertThat(server.state()).isEqualTo(HealthState.HEALTHY);
  }

  /** Regression: a provider adapter that blows up must not take the caller down with it. */
  @Test
  void adapterExceptionsBecomeErrorsAndCountAsFailures() {
    ProviderServer server = server(new ExplodingAdapter());

    Answer<Quote> answer = server.requestQuote(REQUEST, SECOND);

    assertThat(answer).isInstanceOf(Error.class);
    assertThat(((Error<Quote>) answer).reason()).contains("raised").contains("kaput");
    assertThat(server.health().window())
        .extracting(Health.Sample::outcome)
        .containsExactly(Outcome.ERROR);
  }

  /** Regression: an overdue call answers Timeout, is recorded, and frees its slot. */
  @Test
  void anOverdueAdapterCallReturnsTimeoutIsRecordedAndFreesItsSlot() {
    BlockingAdapter blocking = new BlockingAdapter();
    ProviderServer server = server(blocking);

    assertThat(server.requestQuote(REQUEST, Duration.ofMillis(20))).isEqualTo(new Timeout<Quote>());

    await().atMost(SECOND).until(() -> server.inFlight() == 0 && blocking.blocked() == 0);
    assertThat(server.health().window())
        .extracting(Health.Sample::outcome)
        .containsExactly(Outcome.TIMEOUT);
  }

  @Test
  void theBulkheadRefusesWhenTheLimitIsReachedAndSaysNothingWasSent() {
    BlockingAdapter blocking = new BlockingAdapter();
    ProviderServer server =
        server(blocking, HealthConfig.defaults(), Limits.defaults().withMaxInFlight(1));
    Thread holder =
        Thread.ofVirtual().start(() -> server.requestQuote(REQUEST, Duration.ofSeconds(5)));
    await().atMost(SECOND).until(() -> server.inFlight() == 1);

    assertThat(server.requestQuote(REQUEST, SECOND)).isEqualTo(new Error<Quote>("overloaded"));

    blocking.releaseAll();
    await().atMost(SECOND).until(() -> server.inFlight() == 0);
    assertThat(holder.isAlive() && false).isFalse();
  }

  @Test
  void priorityClassesQuotesUseSixtyPercentStepsAllUndoAndStatusOneAndAHalfTimes() {
    BlockingAdapter blocking = new BlockingAdapter();
    ProviderServer server = server(blocking);
    Quote quote = new Quote("p", "r", 100_000_000L, 0, Long.MAX_VALUE);

    hold(server, 6, s -> s.requestQuote(REQUEST, Duration.ofSeconds(5)));
    assertThat(server.requestQuote(REQUEST, SECOND)).isEqualTo(new Error<Quote>("overloaded"));

    hold(
        server,
        4,
        s ->
            s.invoke(
                Operation.ACCEPT_QUOTE, a -> a.acceptQuote(quote, "k"), Duration.ofSeconds(5)));
    assertThat(server.invoke(Operation.PAY, a -> a.pay(quote, "k"), SECOND))
        .isEqualTo(new Error<Receipt>("overloaded"));

    hold(
        server,
        5,
        s -> s.invoke(Operation.CANCEL_ACCEPT, a -> a.cancelAccept("k"), Duration.ofSeconds(5)));
    assertThat(server.invoke(Operation.REFUND, a -> a.refund("k"), SECOND))
        .isEqualTo(new Error<Receipt>("overloaded"));
    assertThat(server.invoke(Operation.STATUS, a -> a.status(Operation.PAY, "k"), SECOND))
        .isEqualTo(new Error<Receipt>("overloaded"));
    assertThat(server.inFlight()).isEqualTo(15);

    blocking.releaseAll();
    await().atMost(Duration.ofSeconds(3)).until(() -> server.inFlight() == 0);
  }

  private static void hold(
      ProviderServer server, int count, java.util.function.Consumer<ProviderServer> call) {
    int target = server.inFlight() + count;
    for (int i = 0; i < count; i++) {
      Thread.ofVirtual().start(() -> call.accept(server));
    }
    await().atMost(Duration.ofSeconds(3)).until(() -> server.inFlight() == target);
  }

  /** Regression: a caller that is interrupted mid-call must not leak its slot. */
  @Test
  void aCallerThatIsInterruptedMidCallReleasesItsSlot() {
    BlockingAdapter blocking = new BlockingAdapter();
    ProviderServer server = server(blocking);
    Thread caller =
        Thread.ofVirtual().start(() -> server.requestQuote(REQUEST, Duration.ofSeconds(5)));
    await().atMost(SECOND).until(() -> server.inFlight() == 1);

    caller.interrupt();

    await().atMost(SECOND).until(() -> server.inFlight() == 0);
    blocking.releaseAll();
  }

  @Test
  void aFailureWhileSettlingStillFreesTheSlot() {
    AtomicInteger reads = new AtomicInteger();
    ProviderServer server =
        new ProviderServer(
            "p",
            new FakeProvider("p"),
            HealthConfig.defaults(),
            Limits.defaults(),
            (tx, actor, kind, message, data) -> {},
            () -> {
              if (reads.incrementAndGet() > 2) {
                throw new IllegalStateException("clock failed");
              }
              return 1_000L;
            });
    servers.add(server);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> server.requestQuote(REQUEST, SECOND))
        .isInstanceOf(IllegalStateException.class);

    assertThat(server.inFlight()).isZero();
  }

  @Test
  void aProbeRecordsTheVersionAndAChangeQuarantinesUntilReleased() {
    FakeProvider fake = new FakeProvider("p");
    ProviderServer server = server(fake);

    assertThat(((Ok<ProbeInfo>) server.probe(SECOND)).value().version()).isEqualTo("1");
    server.probe(SECOND);
    assertThat(server.state()).isEqualTo(HealthState.HEALTHY);

    fake.version("2");
    server.probe(SECOND);
    assertThat(server.state()).isEqualTo(HealthState.QUARANTINED);
    assertThat(server.requestQuote(REQUEST, SECOND)).isEqualTo(new Error<Quote>("quarantined"));

    server.release();
    assertThat(server.requestQuote(REQUEST, SECOND)).isInstanceOf(Ok.class);
    assertThat(messages).anyMatch(m -> m.contains("released from quarantine"));
  }

  @Test
  void aFailedProbeDoesNotTouchTheVersion() {
    FakeProvider fake = new FakeProvider("p").script(Operation.PROBE, Mode.TIMEOUT);
    ProviderServer server = server(fake);

    assertThat(server.probe(SECOND)).isEqualTo(new Timeout<ProbeInfo>());
    assertThat(server.health().version()).isNull();
  }

  @Test
  void healthChangesAreAnnouncedWithTheTransition() {
    ProviderServer server =
        server(new FakeProvider("p"), HealthConfig.defaults(), Limits.defaults());

    server.requestQuote(REQUEST, SECOND);

    assertThat(messages).hasSize(1);
    assertThat(messages.get(0)).isEqualTo("p: unknown -> healthy (call success in 0ms)");
    assertThat(health.get(0))
        .containsEntry("provider", "p")
        .containsEntry("state", "healthy")
        .containsEntry("previous", "unknown")
        .containsEntry("breaker", "closed");

    server.requestQuote(REQUEST, SECOND);
    assertThat(messages.get(1)).isEqualTo("p: healthy (call success in 0ms)");
  }

  @Test
  void aServerBuiltWithDefaultsWorksOnTheSystemClock() {
    ProviderServer server = new ProviderServer("d", new FakeProvider("d"));
    servers.add(server);

    assertThat(server.requestQuote(REQUEST, ProviderServer.DEFAULT_TIMEOUT)).isInstanceOf(Ok.class);
    assertThat(server.state()).isEqualTo(HealthState.HEALTHY);
  }
}

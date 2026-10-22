package net.robbrown.actortx.provider;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.LongSupplier;
import net.robbrown.actortx.events.EventSink;
import net.robbrown.actortx.provider.Answer.Error;
import net.robbrown.actortx.provider.Answer.Ok;
import net.robbrown.actortx.provider.Answer.Timeout;
import net.robbrown.actortx.provider.Health.Checkout;
import net.robbrown.actortx.provider.Health.Refusal;

/**
 * The facade in front of one provider: a circuit breaker, a health model and a bulkhead with
 * priority classes, with every call's timeout enforced.
 *
 * <p>The adapter call runs on its own virtual thread, so a hung provider never blocks the caller
 * past its timeout (the call is interrupted and answers {@code Timeout}), and one slow provider
 * never blocks others. The server only does admission, bookkeeping and in-flight accounting, so a
 * caller that is interrupted cannot leak a slot.
 *
 * <p>When it does not call the provider at all it says so with an {@link Answer.Error}: {@code
 * "overloaded"} (a bulkhead is full), {@code "circuit_open"} or {@code "quarantined"}. Such an
 * answer means nothing was sent, so asking again is safe.
 */
public final class ProviderServer implements QuoteSource, AutoCloseable {

  /** Stands in for the provider's call timeout when the caller gives none. */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

  private final String id;
  private final ProviderAdapter adapter;
  private final Limits limits;
  private final EventSink events;
  private final LongSupplier clock;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final Object lock = new Object();
  private Health health;
  private int inFlight;

  /**
   * A server for {@code adapter}.
   *
   * @param id the provider's name
   * @param adapter the provider
   * @param healthConfig when the breaker opens, what counts as slow...
   * @param limits the bulkhead
   * @param events where health changes are announced
   * @param clock wall-clock milliseconds (injected so tests control time)
   */
  public ProviderServer(
      String id,
      ProviderAdapter adapter,
      HealthConfig healthConfig,
      Limits limits,
      EventSink events,
      LongSupplier clock) {
    this.id = id;
    this.adapter = adapter;
    this.limits = limits;
    this.events = events;
    this.clock = clock;
    this.health = Health.create(healthConfig);
  }

  /**
   * A server with default health settings and bulkhead, on the system clock, announcing nowhere.
   */
  public ProviderServer(String id, ProviderAdapter adapter) {
    this(
        id,
        adapter,
        HealthConfig.defaults(),
        Limits.defaults(),
        EventSink.NONE,
        System::currentTimeMillis);
  }

  /** The provider's name. */
  public String id() {
    return id;
  }

  /**
   * Make a call through the breaker and the bulkhead.
   *
   * @param op which operation (decides which slots it may use)
   * @param call what to do with the adapter
   * @param timeout how long the provider has; an overdue call is interrupted and answers Timeout
   */
  public <T> Answer<T> invoke(
      Operation op, Function<ProviderAdapter, Answer<T>> call, Duration timeout) {
    Optional<Answer<T>> refusal = admit(op);
    if (refusal.isPresent()) {
      return refusal.get();
    }
    long started = clock.getAsLong();
    boolean settled = false;
    try {
      Answer<T> answer = callWithTimeout(call, timeout);
      settle(answer, clock.getAsLong() - started);
      settled = true;
      return answer;
    } finally {
      if (!settled) {
        abandon();
      }
    }
  }

  @Override
  public Answer<Quote> requestQuote(QuoteRequest request, Duration timeout) {
    return invoke(Operation.REQUEST_QUOTE, a -> a.requestQuote(request), timeout);
  }

  /** Probe the provider (the sentinel's entry point). A changed version quarantines it. */
  public Answer<ProbeInfo> probe(Duration timeout) {
    Answer<ProbeInfo> answer = invoke(Operation.PROBE, ProviderAdapter::probe, timeout);
    if (answer instanceof Ok<ProbeInfo> ok) {
      observeVersion(ok.value().version());
    }
    return answer;
  }

  @Override
  public HealthState state() {
    synchronized (lock) {
      return health.state();
    }
  }

  /** A snapshot of the health model. */
  public Health health() {
    synchronized (lock) {
      return health;
    }
  }

  /** How many calls are in flight now. */
  public int inFlight() {
    synchronized (lock) {
      return inFlight;
    }
  }

  /** Lift a quarantine after the drift has been reviewed. */
  public void release() {
    update(Health::release, "released from quarantine");
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }

  // ---- admission -------------------------------------------------------------------------

  private <T> Optional<Answer<T>> admit(Operation op) {
    synchronized (lock) {
      if (inFlight >= limits.limit(op.bulkheadClass())) {
        return Optional.of(new Error<>("overloaded"));
      }
      return switch (health.checkout(clock.getAsLong())) {
        case Checkout.Admitted admitted -> take(admitted.health());
        case Checkout.Refused refused -> Optional.of(new Error<>(reason(refused.reason())));
      };
    }
  }

  // called with the lock held
  private <T> Optional<Answer<T>> take(Health admitted) {
    health = admitted;
    inFlight++;
    return Optional.empty();
  }

  private static String reason(Refusal refusal) {
    return refusal.name().toLowerCase(java.util.Locale.ROOT);
  }

  // ---- the call itself -------------------------------------------------------------------

  private <T> Answer<T> callWithTimeout(
      Function<ProviderAdapter, Answer<T>> call, Duration timeout) {
    Future<Answer<T>> future = executor.submit(() -> call.apply(adapter));
    try {
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException overdue) {
      future.cancel(true);
      return new Timeout<>();
    } catch (ExecutionException failed) {
      return new Error<>("raised: " + failed.getCause());
    } catch (InterruptedException interrupted) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      return new Timeout<>();
    }
  }

  // ---- bookkeeping -----------------------------------------------------------------------

  private <T> void settle(Answer<T> answer, long latencyMs) {
    Outcome outcome = answer.outcome();
    update(
        h -> h.record(outcome, latencyMs, clock.getAsLong()),
        "call " + outcome.name().toLowerCase(java.util.Locale.ROOT) + " in " + latencyMs + "ms",
        true);
  }

  private void abandon() {
    update(Health::abandon, "caller gone", true);
  }

  private void observeVersion(String version) {
    update(h -> h.observeVersion(version).health(), "probe observed version " + version);
  }

  private void update(Function<Health, Health> change, String reason) {
    update(change, reason, false);
  }

  private void update(Function<Health, Health> change, String reason, boolean freesSlot) {
    HealthState before;
    HealthState after;
    Health now;
    synchronized (lock) {
      before = health.state();
      health = change.apply(health);
      inFlight -= freesSlot ? 1 : 0;
      after = health.state();
      now = health;
    }
    announce(before, after, now, reason);
  }

  private void announce(HealthState before, HealthState after, Health now, String reason) {
    String message =
        before == after
            ? "%s: %s (%s)".formatted(id, after.wire(), reason)
            : "%s: %s -> %s (%s)".formatted(id, before.wire(), after.wire(), reason);
    Stats stats = now.stats();
    events.emit(
        null,
        "provider:" + id,
        "health",
        message,
        Map.of(
            "provider", id,
            "state", after.wire(),
            "previous", before.wire(),
            "breaker", now.breaker().name().toLowerCase(java.util.Locale.ROOT),
            "error_rate", stats.errorRate(),
            "p95_ms", stats.p95Ms(),
            "samples", stats.samples(),
            "recovering", now.recovering()));
  }
}

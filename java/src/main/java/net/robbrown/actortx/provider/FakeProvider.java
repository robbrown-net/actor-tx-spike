package net.robbrown.actortx.provider;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.robbrown.actortx.events.EventSink;
import net.robbrown.actortx.money.Money;
import net.robbrown.actortx.provider.Answer.Error;
import net.robbrown.actortx.provider.Answer.InDoubt;
import net.robbrown.actortx.provider.Answer.NotFound;
import net.robbrown.actortx.provider.Answer.Ok;
import net.robbrown.actortx.provider.Answer.Rejected;
import net.robbrown.actortx.provider.Answer.Timeout;
import net.robbrown.actortx.provider.Answer.Unknown;

/**
 * A scriptable fake provider for tests, experiments and the live demo.
 *
 * <p>Each operation has a mode ({@link Mode}) and a script: modes are consumed one per call and the
 * last one sticks, so {@code script(PAY, TIMEOUT, OK)} fails once, then succeeds. Also scriptable:
 * rate, fee, latency, version and quote lifetime. When a request names a currency pair the provider
 * knows, the quoted rate is the market (mid) rate carried through this provider's own spread:
 * {@code mid * rate / refRate}.
 *
 * <p>It narrates what a real service would (receiving, processing, answering) and records every
 * effect, so tests can assert exactly what happened (for example, no double payment). All methods
 * are safe to call from many threads.
 */
public class FakeProvider implements ProviderAdapter {

  private static final long DEFAULT_RATE = Money.RATE_SCALE;
  private static final long DEFAULT_REF_RATE = 108_200_000L;
  private static final long DEFAULT_TTL_MS = 60_000L;

  private record EffectKey(Operation op, String key) {}

  private final String id;
  private final EventSink events;
  private final Object lock = new Object();
  private final Map<Operation, Deque<Mode>> scripts = new java.util.HashMap<>();
  private final Map<EffectKey, Receipt> effects = new HashMap<>();
  private final java.util.Set<Operation> unsupported = new java.util.HashSet<>();
  private final Map<Operation, Integer> calls = new java.util.HashMap<>();
  private Map<String, Long> pairMids = new HashMap<>();
  private long rate = DEFAULT_RATE;
  private long refRate = DEFAULT_REF_RATE;
  private long fee;
  private long latencyMs;
  private long quoteTtlMs = DEFAULT_TTL_MS;
  private String version = "1";

  /** A fake called {@code id} that narrates to {@code events}. */
  public FakeProvider(String id, EventSink events) {
    this.id = id;
    this.events = events;
  }

  /** A fake that narrates nowhere. */
  public FakeProvider(String id) {
    this(id, EventSink.NONE);
  }

  /** Its name. */
  public String id() {
    return id;
  }

  // ---- scripting -------------------------------------------------------------------------

  /** The quoted rate as a decimal string such as {@code "1.0820"}. */
  public FakeProvider rate(String decimal) {
    return rateScaled(Money.rate(decimal));
  }

  /** The quoted rate, scaled by 10^8. */
  public FakeProvider rateScaled(long scaled) {
    synchronized (lock) {
      rate = scaled;
    }
    return this;
  }

  /** The fee in major units as a decimal string such as {@code "1.50"}. */
  public FakeProvider fee(String decimal) {
    synchronized (lock) {
      fee = Money.minor(decimal);
    }
    return this;
  }

  /** How long each call takes. */
  public FakeProvider latencyMs(long ms) {
    synchronized (lock) {
      latencyMs = ms;
    }
    return this;
  }

  /** The version a probe reports. */
  public FakeProvider version(String newVersion) {
    synchronized (lock) {
      version = newVersion;
    }
    return this;
  }

  /** How long a quote stays valid (negative: already expired). */
  public FakeProvider quoteTtlMs(long ms) {
    synchronized (lock) {
      quoteTtlMs = ms;
    }
    return this;
  }

  /**
   * Market rates per pair ({@code "USD/EUR"} to a scaled rate) and the rate that quotes the mid.
   */
  public FakeProvider pairs(Map<String, Long> mids, long referenceRate) {
    synchronized (lock) {
      pairMids = new HashMap<>(mids); // a HashMap: the pair of a request with no currencies is null
      refRate = referenceRate;
    }
    return this;
  }

  /** What the next calls of {@code op} do: one mode per call, the last one sticks. */
  public FakeProvider script(Operation op, Mode... modes) {
    synchronized (lock) {
      scripts.put(op, new ArrayDeque<>(List.of(modes)));
    }
    return this;
  }

  /** Every operation behaves again: forget all scripts. */
  public FakeProvider resetScripts() {
    synchronized (lock) {
      scripts.clear();
    }
    return this;
  }

  /** The current rate, scaled by 10^8. */
  public long currentRate() {
    synchronized (lock) {
      return rate;
    }
  }

  /** The current fee in minor units. */
  public long currentFee() {
    synchronized (lock) {
      return fee;
    }
  }

  /** The current latency. */
  public long currentLatencyMs() {
    synchronized (lock) {
      return latencyMs;
    }
  }

  // ---- observing -------------------------------------------------------------------------

  /** Every effect that actually happened, by operation and key. */
  public Map<String, Receipt> effects() {
    synchronized (lock) {
      Map<String, Receipt> copy = new HashMap<>();
      effects.forEach((k, v) -> copy.put(k.op().wire() + "|" + k.key(), v));
      return copy;
    }
  }

  /** How many effects of this operation actually happened. */
  public long effectCount(Operation op) {
    synchronized (lock) {
      return effects.keySet().stream().filter(k -> k.op() == op).count();
    }
  }

  /**
   * The misbehaviours in force: for each operation that is scripted to do something other than
   * behave, what it does next.
   */
  public Map<Operation, Mode> faults() {
    synchronized (lock) {
      Map<Operation, Mode> faults =
          new java.util.TreeMap<>(java.util.Comparator.comparing(Operation::wire));
      scripts.forEach(
          (op, script) -> {
            Mode next = script.peekFirst();
            if (next != null && next.kind() != Mode.Kind.OK) {
              faults.put(op, next);
            }
          });
      return faults;
    }
  }

  /** How many times each operation was invoked (including failed and retried calls). */
  public Map<Operation, Integer> calls() {
    synchronized (lock) {
      return new java.util.HashMap<>(calls);
    }
  }

  // ---- the provider operations -----------------------------------------------------------

  @Override
  public Answer<Quote> requestQuote(QuoteRequest request) {
    Mode mode = begin(Operation.REQUEST_QUOTE);
    announce(request.id(), "received", "received a quote request for " + amountText(request));
    delay(request.id());
    return answered(request.id(), Operation.REQUEST_QUOTE, quoteAnswer(mode, request));
  }

  /** This provider cannot do these operations (a capability it lacks). */
  public FakeProvider without(Operation... ops) {
    synchronized (lock) {
      unsupported.addAll(List.of(ops));
    }
    return this;
  }

  @Override
  public boolean supports(Operation op) {
    synchronized (lock) {
      return !unsupported.contains(op);
    }
  }

  /** Any operation, including ones the standard payment does not know: it is scriptable too. */
  @Override
  public Answer<Receipt> perform(Operation op, Quote quote, String key) {
    return effect(op, quote, key);
  }

  @Override
  public Answer<Receipt> acceptQuote(Quote quote, String key) {
    return effect(Operation.ACCEPT_QUOTE, quote, key);
  }

  @Override
  public Answer<Receipt> authorise(Quote quote, String key) {
    return effect(Operation.AUTHORISE, quote, key);
  }

  @Override
  public Answer<Receipt> pay(Quote quote, String key) {
    return effect(Operation.PAY, quote, key);
  }

  @Override
  public Answer<Receipt> settle(String key) {
    return effect(Operation.SETTLE, null, key);
  }

  @Override
  public Answer<Receipt> cancelAccept(String key) {
    return effect(Operation.CANCEL_ACCEPT, null, key);
  }

  @Override
  public Answer<Receipt> voidAuthorisation(String key) {
    return effect(Operation.VOID_AUTHORISATION, null, key);
  }

  @Override
  public Answer<Receipt> refund(String key) {
    return effect(Operation.REFUND, null, key);
  }

  @Override
  public Answer<Receipt> status(Operation op, String key) {
    Mode mode = begin(Operation.STATUS);
    String tx = txOf(key);
    announce(tx, "received", "received a status lookup for " + op.wire() + " " + key);
    delay(tx);
    return answered(tx, Operation.STATUS, statusAnswer(mode, op, key));
  }

  @Override
  public Answer<ProbeInfo> probe() {
    Mode mode = begin(Operation.PROBE);
    announce(null, "received", "received a health probe");
    delay(null);
    return answered(null, Operation.PROBE, probeAnswer(mode));
  }

  // ---- answers ---------------------------------------------------------------------------

  private Answer<Quote> quoteAnswer(Mode mode, QuoteRequest request) {
    return switch (mode.kind()) {
      case OK -> new Ok<>(quoteFor(request));
      case REJECT -> new Rejected<>("no_liquidity");
      default -> failure(mode);
    };
  }

  private Quote quoteFor(QuoteRequest request) {
    synchronized (lock) {
      return new Quote(
          id, request.id(), rateFor(request), fee, System.currentTimeMillis() + quoteTtlMs);
    }
  }

  // called with the lock held
  private long rateFor(QuoteRequest request) {
    Long mid = pairMids.get(request.pair());
    return mid == null ? rate : Money.divHalfEven(mid * rate, refRate);
  }

  private Answer<Receipt> effect(Operation op, Quote quote, String key) {
    Mode mode = begin(op);
    String tx = txOf(key);
    announce(tx, "received", "received " + op.wire() + " (key " + key + ")");
    delay(tx);
    Optional<Answer<Receipt>> refusal = refusal(mode, quote);
    return answered(tx, op, refusal.orElseGet(() -> perform(mode, op, quote, key)));
  }

  private Optional<Answer<Receipt>> refusal(Mode mode, Quote quote) {
    if (mode.failing()) {
      return Optional.of(failure(mode));
    }
    if (expired(quote)) {
      return Optional.of(new Rejected<>("quote_expired"));
    }
    return mode.kind() == Mode.Kind.REJECT
        ? Optional.of(new Rejected<>("declined"))
        : Optional.empty();
  }

  private static boolean expired(Quote quote) {
    return quote != null && quote.expired(System.currentTimeMillis());
  }

  private Answer<Receipt> perform(Mode mode, Operation op, Quote quote, String key) {
    Receipt receipt = remember(op, quote, key);
    return mode.kind() == Mode.Kind.IN_DOUBT ? new InDoubt<>() : new Ok<>(receipt);
  }

  // The first time an effect is performed it is recorded; asked again, the same receipt comes back.
  private Receipt remember(Operation op, Quote quote, String key) {
    synchronized (lock) {
      return effects.computeIfAbsent(
          new EffectKey(op, key),
          k ->
              new Receipt(
                  id,
                  op,
                  key,
                  quote == null ? null : quote.rate(),
                  quote == null ? null : quote.fee()));
    }
  }

  private Answer<Receipt> statusAnswer(Mode mode, Operation op, String key) {
    return switch (mode.kind()) {
      case OK -> held(op, key);
      case UNKNOWN -> new Unknown<>();
      default -> failure(mode);
    };
  }

  private Answer<Receipt> held(Operation op, String key) {
    synchronized (lock) {
      Receipt receipt = effects.get(new EffectKey(op, key));
      return receipt == null ? new NotFound<>() : new Ok<>(receipt);
    }
  }

  private Answer<ProbeInfo> probeAnswer(Mode mode) {
    synchronized (lock) {
      return mode.kind() == Mode.Kind.OK
          ? new Ok<>(new ProbeInfo(version, latencyMs))
          : failure(mode);
    }
  }

  private static <T> Answer<T> failure(Mode mode) {
    return mode.kind() == Mode.Kind.TIMEOUT ? new Timeout<>() : new Error<>(mode.reason());
  }

  // ---- mechanics -------------------------------------------------------------------------

  // Take this call's mode (consuming scripted sequences) and count the call.
  private Mode begin(Operation op) {
    synchronized (lock) {
      calls.merge(op, 1, Integer::sum);
      Deque<Mode> script = scripts.get(op);
      if (script == null || script.isEmpty()) {
        return Mode.OK;
      }
      return script.size() == 1 ? script.peekFirst() : script.pollFirst();
    }
  }

  private void delay(String tx) {
    long ms = currentLatencyMs();
    if (ms > 0) {
      announce(tx, "processing", "processing (takes ~" + ms + "ms)");
      sleep(ms);
    }
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private <T> Answer<T> answered(String tx, Operation op, Answer<T> answer) {
    events.emit(
        tx,
        "provider:" + id,
        "responded",
        op.wire() + " -> " + describe(answer),
        Map.of("op", op.wire(), "result", answer.tag()));
    return answer;
  }

  private void announce(String tx, String kind, String message) {
    events.emit(tx, "provider:" + id, kind, message, Map.of());
  }

  private static <T> String describe(Answer<T> answer) {
    return switch (answer) {
      case Ok<T> ok -> describeOk(ok.value());
      case Rejected<T> rejected -> "rejected (" + rejected.reason() + ")";
      case Error<T> error -> "error (" + error.reason() + ")";
      default -> answer.tag();
    };
  }

  private static String describeOk(Object value) {
    return value instanceof Quote q
        ? "quote rate " + Money.formatRate(q.rate()) + " fee " + Money.format(q.fee())
        : "ok";
  }

  private static String amountText(QuoteRequest request) {
    return request.amount() == null ? "an unspecified amount" : Money.format(request.amount());
  }

  private static String txOf(String key) {
    return key.split(":", 2)[0];
  }
}

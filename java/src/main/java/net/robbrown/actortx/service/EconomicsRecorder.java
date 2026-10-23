package net.robbrown.actortx.service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.robbrown.actortx.economics.EconomicsLedger;
import net.robbrown.actortx.economics.Measurement;
import net.robbrown.actortx.economics.Outcome;
import net.robbrown.actortx.economics.Outcome.Status;
import net.robbrown.actortx.events.Event;

/**
 * Turns the event stream into priced outcomes: what each transaction used (time, provider calls,
 * state changes, compensations) is counted as it happens and handed to the ledger when it ends.
 */
public final class EconomicsRecorder implements Consumer<Event> {

  private static final Map<String, Status> ENDS =
      Map.of(
          "tx_completed", Status.COMPLETED,
          "tx_failed", Status.FAILED,
          "needs_review", Status.NEEDS_REVIEW);

  /** What one transaction has used so far. */
  private static final class Usage {
    private final long startedMs;
    private volatile String provider = "";
    private volatile String merchant = "";
    private volatile long calls;
    private volatile long changes;
    private volatile long compensations;

    Usage(long startedMs) {
      this.startedMs = startedMs;
    }
  }

  private final EconomicsLedger ledger;
  private final Map<String, Usage> open = new ConcurrentHashMap<>();

  /** A recorder pricing into {@code ledger}. */
  public EconomicsRecorder(EconomicsLedger ledger) {
    this.ledger = ledger;
  }

  /** The ledger being kept. */
  public EconomicsLedger ledger() {
    return ledger;
  }

  @Override
  public void accept(Event event) {
    if (event.txId() == null) {
      return;
    }
    if ("tx_received".equals(event.kind())) {
      begin(event);
    } else {
      Optional.ofNullable(open.get(event.txId())).ifPresent(usage -> count(usage, event));
    }
  }

  private void begin(Event event) {
    Usage usage = new Usage(event.atMs());
    usage.merchant = merchantOf(event);
    open.put(event.txId(), usage);
  }

  private static final Map<String, BiConsumer<Usage, Event>> COUNTERS =
      Map.of(
          "journal", (u, e) -> u.calls++,
          "step_succeeded", (u, e) -> u.changes++,
          "reconciled", (u, e) -> u.changes++,
          "undone", (u, e) -> u.compensations++,
          "provider_chosen", (u, e) -> u.provider = String.valueOf(e.data().get("provider")));

  private void count(Usage usage, Event event) {
    COUNTERS.getOrDefault(event.kind(), (u, e) -> finishIfEnd(u, e)).accept(usage, event);
  }

  private void finishIfEnd(Usage usage, Event event) {
    Status status = ENDS.get(event.kind());
    if (status != null) {
      open.remove(event.txId());
      ledger.record(
          new Outcome(
              event.txId(),
              status,
              usage.provider,
              usage.merchant,
              new Measurement(
                  0,
                  0,
                  event.atMs() - usage.startedMs,
                  usage.calls,
                  0,
                  0,
                  usage.changes,
                  usage.compensations),
              null,
              0,
              0));
    }
  }

  private static String merchantOf(Event event) {
    Object request = event.data().get("request");
    Object merchant = request instanceof Map<?, ?> map ? map.get("merchant_id") : null;
    return merchant == null ? "" : merchant.toString();
  }
}

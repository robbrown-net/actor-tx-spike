package net.robbrown.actortx.economics;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The economics ledger: prices each finished transaction with a {@link RateCard} and keeps running
 * totals, overall and per provider, plus a bounded tail of recent entries.
 *
 * <p>The counterfactual behind "saved" is the median quote, and the baseline is always reported
 * alongside. Thread-safe: every method is synchronized on the ledger, so records may come from many
 * threads at once. Subscribing to events and announcing results is the caller's job.
 */
public final class EconomicsLedger {

  private static final int RECENT = 200;

  private final RateCard card;
  private final LongSupplier clockMs;
  private final Map<String, Totals> byProvider = new HashMap<>();
  private final Deque<Entry> tail = new ArrayDeque<>();
  private long sinceMs;
  private Totals total = Totals.BLANK;

  /** A ledger that prices with {@code card} and reads the system clock for platform time. */
  public EconomicsLedger(RateCard card) {
    this(card, System::currentTimeMillis);
  }

  /** A ledger that prices with {@code card} and reads {@code clockMs} for platform time. */
  public EconomicsLedger(RateCard card, LongSupplier clockMs) {
    this.card = card;
    this.clockMs = clockMs;
    this.sinceMs = clockMs.getAsLong();
  }

  /** Prices one finished transaction and adds it to the totals. */
  public synchronized void record(Outcome outcome) {
    Value value = Value.of(outcome);
    Cost cost = Costs.price(outcome.usage(), card);
    Totals single = Totals.of(outcome.status(), value, cost);
    total = total.plus(single);
    if (outcome.provider() != null) {
      byProvider.merge(outcome.provider(), single, Totals::plus);
    }
    addRecent(
        new Entry(
            outcome.txId(), outcome.status(), outcome.provider(), outcome.merchant(), cost, value));
  }

  /** The totals so far, overall and per provider, with the platform cost of the elapsed time. */
  public synchronized Summary summary() {
    long platform = Costs.platform(card, clockMs.getAsLong() - sinceMs);
    Map<String, Figures> providers = new HashMap<>();
    byProvider.forEach((id, totals) -> providers.put(id, Figures.of(totals, 0L)));
    return new Summary(sinceMs, Figures.of(total, platform), Map.copyOf(providers), card);
  }

  /** The last {@code n} entries, oldest first (at most the bounded tail of 200). */
  public synchronized List<Entry> recent(int n) {
    int skip = Math.max(0, tail.size() - n);
    return tail.stream().skip(skip).toList();
  }

  /** Forgets every total and entry, and starts counting again from now. */
  public synchronized void reset() {
    total = Totals.BLANK;
    byProvider.clear();
    tail.clear();
    sinceMs = clockMs.getAsLong();
  }

  private void addRecent(Entry entry) {
    tail.addLast(entry);
    if (tail.size() > RECENT) {
      tail.removeFirst();
    }
  }

  /**
   * One priced transaction, as kept in the recent tail.
   *
   * @param txId the transaction
   * @param status how it finished
   * @param provider the provider, or {@code null}
   * @param merchant the merchant, or {@code null}
   * @param cost what it cost to run
   * @param value what it earned
   */
  public record Entry(
      String txId,
      net.robbrown.actortx.payment.Outcome status,
      String provider,
      String merchant,
      Cost cost,
      Value value) {}
}

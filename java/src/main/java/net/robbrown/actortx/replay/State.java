package net.robbrown.actortx.replay;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The system as it stood at a moment, folded from the records up to that point.
 *
 * @param asOf the position asked for, and the last record it reached
 * @param transactions every transaction started by then, summarised as of then
 * @param inFlight the open transactions, sorted
 * @param outcomes how many transactions ended in each outcome
 * @param providers the last health and last observed quote of every provider
 * @param policy the policy recorded with the latest transaction (empty if none)
 * @param guarantee the guarantee recorded with the latest transaction (empty if none)
 * @param config the configuration recorded with the latest transaction (empty if none)
 * @param controls the operator actions so far
 * @param batches the bulk batches so far
 * @param economics the price-discovery totals so far
 */
public record State(
    AsOf asOf,
    Map<String, TxSummary> transactions,
    List<String> inFlight,
    Map<Outcome, Long> outcomes,
    Map<String, ProviderView> providers,
    Map<String, Object> policy,
    Map<String, Object> guarantee,
    Map<String, Object> config,
    List<Control> controls,
    List<Batch> batches,
    Economics economics) {

  private static final List<String> HEALTH_KEYS =
      List.of("state", "breaker", "error_rate", "p95_ms", "version", "recovering");
  private static final List<String> QUOTE_KEYS =
      List.of("outcome", "rate", "fee", "latency_ms", "state");

  /**
   * Where the state was taken.
   *
   * @param position the position asked for
   * @param lastN the audit record number of the last record reached, or null
   * @param lastAtMs the time of the last record reached, or null
   */
  public record AsOf(Position position, Long lastN, Long lastAtMs) {}

  /**
   * What is known of one provider.
   *
   * @param health its last health record (empty if none)
   * @param lastQuote its last observed quote (empty if none)
   */
  public record ProviderView(Map<String, Object> health, Map<String, Object> lastQuote) {}

  /**
   * An operator action.
   *
   * @param atMs when it happened
   * @param command what was commanded
   */
  public record Control(Long atMs, Map<String, Object> command) {}

  /**
   * Price-discovery totals.
   *
   * @param transactions how many transactions were costed
   * @param savedVsMedian the total saved against the median quote, minor units
   * @param margin the total margin, minor units
   * @param costPicos the total cost, in pico-units
   */
  public record Economics(long transactions, long savedVsMedian, long margin, long costPicos) {

    /** These totals plus one costed transaction's {@code value} and {@code cost}. */
    Economics plus(Map<String, Object> costed) {
      Map<String, Object> value = Fields.map(costed.get("value"));
      Map<String, Object> cost = Fields.map(costed.get("cost"));
      return new Economics(
          transactions + 1,
          savedVsMedian + Fields.lngOr(value.get("saved_vs_median"), 0),
          margin + Fields.lngOr(value.get("margin"), 0),
          costPicos + Fields.lngOr(cost.get("total"), 0));
    }
  }

  /**
   * The state at a position.
   *
   * @param day the day
   * @param position the moment
   * @return the state
   */
  public static State at(Day day, Position position) {
    List<Map<String, Object>> upto = day.records().stream().takeWhile(position::reaches).toList();
    Map<String, TxSummary> txs = summaries(upto);
    return new State(
        new AsOf(
            position,
            Fields.lng(Fields.last(upto).get("n")),
            Fields.lng(Fields.last(upto).get("at"))),
        txs,
        inFlight(txs),
        Tx.frequencies(txs.values()),
        providers(upto),
        latest(upto, "policy"),
        latest(upto, "guarantee"),
        latest(upto, "config"),
        controls(upto),
        Batch.of(upto),
        economics(upto));
  }

  private static Map<String, TxSummary> summaries(List<Map<String, Object>> records) {
    Day sub = Day.of(records, Optional.empty());
    Map<String, TxSummary> out = new TreeMap<>();
    for (String id : sub.txOrder()) {
      out.put(id, Tx.summarize(sub.byTx().get(id)));
    }
    return out;
  }

  private static List<String> inFlight(Map<String, TxSummary> txs) {
    return txs.values().stream()
        .filter(s -> s.outcome() == Outcome.OPEN)
        .map(TxSummary::id)
        .sorted()
        .toList();
  }

  private static Map<String, ProviderView> providers(List<Map<String, Object>> records) {
    Map<String, Map<String, Object>> health = new HashMap<>();
    Map<String, Map<String, Object>> quotes = new HashMap<>();
    for (Map<String, Object> record : records) {
      collectProvider(record, health, quotes);
    }
    Set<String> ids = new TreeSet<>(health.keySet());
    ids.addAll(quotes.keySet());
    Map<String, ProviderView> out = new TreeMap<>();
    for (String id : ids) {
      out.put(
          id,
          new ProviderView(health.getOrDefault(id, Map.of()), quotes.getOrDefault(id, Map.of())));
    }
    return out;
  }

  private static void collectProvider(
      Map<String, Object> record,
      Map<String, Map<String, Object>> health,
      Map<String, Map<String, Object>> quotes) {
    Map<String, Object> d = Fields.data(record);
    if (Fields.is(record, "health")) {
      health.put(providerOf(d), Fields.pick(d, HEALTH_KEYS));
    } else if (Fields.is(record, "quote_observed")) {
      quotes.put(providerOf(d), quoteView(record));
    }
  }

  private static String providerOf(Map<String, Object> d) {
    return Objects.toString(d.get("provider"), "");
  }

  private static Map<String, Object> quoteView(Map<String, Object> record) {
    Map<String, Object> view = Fields.pick(Fields.data(record), QUOTE_KEYS);
    view.put("at", record.get("at"));
    return view;
  }

  private static Map<String, Object> latest(List<Map<String, Object>> records, String field) {
    return Fields.map(Fields.data(Tx.lastOf(records, "tx_received")).get(field));
  }

  private static List<Control> controls(List<Map<String, Object>> records) {
    return Tx.all(records, "control").stream()
        .map(r -> new Control(Fields.lng(r.get("at")), Fields.map(Fields.data(r).get("command"))))
        .toList();
  }

  private static Economics economics(List<Map<String, Object>> records) {
    return Tx.all(records, "tx_costed").stream()
        .map(Fields::data)
        .filter(d -> d.containsKey("value") && d.containsKey("cost"))
        .reduce(new Economics(0, 0, 0, 0), Economics::plus, (a, b) -> a);
  }
}

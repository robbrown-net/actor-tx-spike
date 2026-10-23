package net.robbrown.actortx.replay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * A recorded day, built the way the live system narrates it: five transactions (happy, failover,
 * review, failed, mixed) and a health record. Also the helpers that break a good day in one way.
 */
final class ReplayFixtures {

  private ReplayFixtures() {}

  /** A map from alternating keys and values, keeping order and nulls. */
  static Map<String, Object> m(Object... keysAndValues) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      out.put((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return out;
  }

  /** A copy of a record with the value at a path of keys replaced. */
  static Map<String, Object> with(Map<String, Object> record, Object value, String... path) {
    Map<String, Object> copy = new LinkedHashMap<>(record);
    if (path.length == 1) {
      copy.put(path[0], value);
    } else {
      String head = path[0];
      Map<String, Object> inner = Fields.map(record.get(head));
      copy.put(head, with(inner, value, Arrays.copyOfRange(path, 1, path.length)));
    }
    return copy;
  }

  /** The records with each matching one replaced. */
  static List<Map<String, Object>> edit(
      List<Map<String, Object>> records,
      Predicate<Map<String, Object>> match,
      UnaryOperator<Map<String, Object>> change) {
    return records.stream().map(r -> match.test(r) ? change.apply(r) : r).toList();
  }

  /** The records without the matching ones. */
  static List<Map<String, Object>> drop(
      List<Map<String, Object>> records, Predicate<Map<String, Object>> match) {
    return records.stream().filter(r -> !match.test(r)).toList();
  }

  /** The first record that matches. */
  static Map<String, Object> find(
      List<Map<String, Object>> records, Predicate<Map<String, Object>> match) {
    return records.stream().filter(match).findFirst().orElseThrow();
  }

  /** Records of a kind whose transaction ends with the given suffix. */
  static Predicate<Map<String, Object>> kind(String kind, String txSuffix) {
    return r -> kind.equals(r.get("kind")) && String.valueOf(r.get("tx")).endsWith(txSuffix);
  }

  /** A transaction's ids and what each record holds, built in narrative order. */
  static final class Log {
    private final List<Map<String, Object>> records = new ArrayList<>();

    /** Append one record: numbered, stamped 10 ms after the last, about a transaction. */
    Log add(String kind, String tx, Map<String, Object> data) {
      long n = records.size() + 1L;
      Map<String, Object> record = new LinkedHashMap<>();
      record.put("n", n);
      record.put("seq", n);
      record.put("at", 1_000 + 10 * n);
      record.put("tx", tx);
      record.put("actor", "payment");
      record.put("kind", kind);
      record.put("message", kind);
      record.put("data", data);
      records.add(record);
      return this;
    }

    List<Map<String, Object>> records() {
      return records;
    }
  }

  /** The five transactions and a health record, as one recorded day. */
  static List<Map<String, Object>> goodDay() {
    Log log = new Log();
    happy(log);
    failover(log);
    review(log);
    failed(log);
    mixed(log);
    log.add("health", null, health("best"));
    return log.records();
  }

  /** The policy as recorded: cost only, with a 10% limit on the gap to the best. */
  static Map<String, Object> policy() {
    return m(
        "weights",
        m("cost", 1.0, "speed", 0.0, "reliability", 0.0),
        "min_validity_ms",
        0,
        "max_latency_ms",
        null,
        "max_bps_from_best",
        1_000.0,
        "exclude_states",
        List.of());
  }

  /** The tx_received data for a transaction, with its eligibility allowing the providers. */
  static Map<String, Object> received(long amount, String... providers) {
    return m(
        "request", m("amount", amount, "merchant_id", "acme"),
        "policy", policy(),
        "config", m("max_attempts", 3),
        "providers", List.of(providers),
        "eligibility",
            m(
                "permitted?", true,
                "allowed_providers", List.of(providers),
                "regions", List.of("IN"),
                "logs", List.of("GLOBAL"),
                "reasons", List.of()));
  }

  /** A guarantee as recorded with a transaction. */
  static Map<String, Object> guarantee() {
    return m("merchant_id", "acme", "period", "W1", "rate", 105_000_000L);
  }

  /** A quote that was given, expiring well after the narrative. */
  static void quote(Log log, String tx, String provider, long rate, long latency) {
    log.add(
        "quote_observed",
        tx,
        m(
            "provider",
            provider,
            "request_id",
            tx,
            "outcome",
            "quote",
            "rate",
            rate,
            "fee",
            0,
            "expires_at_ms",
            61_000,
            "state",
            "healthy",
            "latency_ms",
            latency,
            "observed_at_ms",
            1_000));
  }

  /** A provider's answer that was not a quote. */
  static void refused(Log log, String tx, String provider, String outcome, String reason) {
    log.add(
        "quote_observed",
        tx,
        m(
            "provider",
            provider,
            "outcome",
            outcome,
            "reason",
            reason,
            "state",
            "healthy",
            "latency_ms",
            10,
            "observed_at_ms",
            1_000));
  }

  /** A selection round starts: the quotes that follow belong to it. */
  static void selectStart(Log log, String tx) {
    log.add("select_started", tx, m());
  }

  /** The selection that was made, with the ranking given as provider and net pairs. */
  static void ranked(Log log, String tx, Object... providerAndNet) {
    List<Map<String, Object>> ranking = new ArrayList<>();
    for (int i = 0; i < providerAndNet.length; i += 2) {
      ranking.add(m("provider", providerAndNet[i], "net", providerAndNet[i + 1]));
    }
    log.add("selection_made", tx, m("ranking", ranking));
  }

  /** One step as the payment narrates it: started, or succeeded, or failed. */
  static void step(Log log, String tx, String provider, String op, String kind) {
    String short_ = "accept_quote".equals(op) ? "accept" : op;
    log.add(kind, tx, m("op", op, "provider", provider, "key", tx + ":" + provider + ":" + short_));
  }

  /** The four steps of a successful payment at one provider. */
  static void completeSteps(Log log, String tx, String provider) {
    for (String op : List.of("accept_quote", "authorise", "pay", "settle")) {
      step(log, tx, provider, op, "step_started");
      step(log, tx, provider, op, "step_succeeded");
    }
  }

  static void happy(Log log) {
    log.add(
        "tx_received",
        "happy",
        with(received(100_000, "best", "second", "third"), guarantee(), "guarantee"));
    selectStart(log, "happy");
    quote(log, "happy", "best", 110_000_000L, 20);
    quote(log, "happy", "second", 105_000_000L, 30);
    quote(log, "happy", "third", 100_000_000L, 40);
    ranked(log, "happy", "best", 110_000L, "second", 105_000L, "third", 100_000L);
    log.add("provider_chosen", "happy", m("provider", "best"));
    completeSteps(log, "happy", "best");
    log.add("tx_completed", "happy", m());
    log.add(
        "guarantee_margin",
        "happy",
        m("guaranteed_net", 105_000L, "achieved_net", 110_000L, "margin", 5_000L));
    log.add(
        "tx_economics",
        "happy",
        m("provider", "best", "margin", 5_000L, "discovery", m("chosen_net", 110_000L)));
    log.add(
        "tx_costed",
        "happy",
        m("value", m("saved_vs_median", 2_000L, "margin", 5_000L), "cost", m("total", 1_200L)));
  }

  static void failover(Log log) {
    log.add("tx_received", "failover", received(200_000, "best", "second"));
    selectStart(log, "failover");
    quote(log, "failover", "best", 110_000_000L, 20);
    quote(log, "failover", "second", 105_000_000L, 30);
    ranked(log, "failover", "best", 220_000L, "second", 210_000L);
    log.add("provider_chosen", "failover", m("provider", "best"));
    step(log, "failover", "best", "accept_quote", "step_started");
    step(log, "failover", "best", "accept_quote", "step_succeeded");
    step(log, "failover", "best", "authorise", "step_started");
    step(log, "failover", "best", "authorise", "step_failed");
    log.add("undone", "failover", m("op", "cancel_accept", "provider", "best"));
    log.add("failover", "failover", m("from", "best", "to", "second"));
    log.add("provider_chosen", "failover", m("provider", "second"));
    completeSteps(log, "failover", "second");
    log.add(
        "tx_economics",
        "failover",
        m("provider", "second", "discovery", m("chosen_net", 210_000L)));
    log.add("tx_completed", "failover", m());
  }

  static void review(Log log) {
    log.add("tx_received", "review", received(300_000, "best"));
    selectStart(log, "review");
    quote(log, "review", "best", 110_000_000L, 20);
    ranked(log, "review", "best", 330_000L);
    log.add("provider_chosen", "review", m("provider", "best"));
    step(log, "review", "best", "accept_quote", "step_started");
    step(log, "review", "best", "accept_quote", "step_succeeded");
    step(log, "review", "best", "authorise", "step_started");
    step(log, "review", "best", "authorise", "step_succeeded");
    step(log, "review", "best", "pay", "step_started");
    log.add("in_doubt", "review", m("op", "pay", "provider", "best"));
    log.add("needs_review", "review", m());
  }

  static void failed(Log log) {
    log.add("tx_received", "failed", received(400_000, "a", "b"));
    selectStart(log, "failed");
    quote(log, "failed", "a", 110_000_000L, 20);
    quote(log, "failed", "b", 100_000_000L, 30);
    ranked(log, "failed", "a", 440_000L, "b", 400_000L);
    log.add("provider_chosen", "failed", m("provider", "a"));
    step(log, "failed", "a", "accept_quote", "step_started");
    step(log, "failed", "a", "accept_quote", "step_failed");
    log.add("provider_chosen", "failed", m("provider", "b"));
    step(log, "failed", "b", "accept_quote", "step_started");
    step(log, "failed", "b", "accept_quote", "step_failed");
    log.add("tx_failed", "failed", m());
  }

  static void mixed(Log log) {
    log.add("tx_received", "mixed", received(500_000, "a", "b", "c", "d"));
    selectStart(log, "mixed");
    quote(log, "mixed", "a", 110_000_000L, 20);
    refused(log, "mixed", "b", "rejected", "no_liquidity");
    refused(log, "mixed", "c", "error", "down");
    refused(log, "mixed", "d", "timeout", null);
    ranked(log, "mixed", "a", 550_000L);
    log.add("provider_chosen", "mixed", m("provider", "a"));
    completeSteps(log, "mixed", "a");
    log.add("tx_completed", "mixed", m());
  }

  /** A health record for one provider (no transaction). */
  static Map<String, Object> health(String provider) {
    return m(
        "provider",
        provider,
        "state",
        "healthy",
        "breaker",
        "closed",
        "error_rate",
        0.0,
        "p95_ms",
        20,
        "version",
        "1",
        "recovering",
        0);
  }
}

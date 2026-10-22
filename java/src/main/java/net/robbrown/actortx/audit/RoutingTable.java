package net.robbrown.actortx.audit;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Which regional logs each open transaction belongs in. A decision ({@code tx_received} or {@code
 * tx_resumed}, carrying {@code data.eligibility.logs}) teaches the routing; the transaction's
 * costing closes it, unless it went to review, in which case its resolution does. The same rules
 * rebuild the table from the logs after a restart.
 */
final class RoutingTable {

  /** The log that always exists and takes everything without a known home. */
  static final String GLOBAL = "GLOBAL";

  private static final String TX_RECEIVED = "tx_received";
  private static final String TX_RESUMED = "tx_resumed";
  private static final String NEEDS_REVIEW = "needs_review";
  private static final String TX_COSTED = "tx_costed";
  private static final String REVIEW_RESOLVED = "review_resolved";

  private final Map<String, List<String>> logsByTx = new HashMap<>();
  private final Set<String> reviewing = new HashSet<>();

  /**
   * Learn from an event before it is routed.
   *
   * @param kind what happened
   * @param tx the transaction it is about, or null
   * @param data the event's data, which may carry the decision's logs
   */
  void learn(String kind, String tx, Object data) {
    if (isDecision(kind)) {
      logsOf(data).ifPresent(logs -> logsByTx.put(tx, logs));
    } else if (NEEDS_REVIEW.equals(kind)) {
      reviewing.add(tx);
    }
  }

  /**
   * The logs an event goes to: the transaction's logs, or the global log when none are known or the
   * event is not about a transaction.
   *
   * @param tx the transaction, or null
   * @return the names of the logs
   */
  List<String> targets(String tx) {
    if (tx == null) {
      return List.of(GLOBAL);
    }
    return logsByTx.getOrDefault(tx, List.of(GLOBAL));
  }

  /**
   * Close a transaction's routing after its event is recorded.
   *
   * @param kind what happened
   * @param tx the transaction it is about, or null
   */
  void close(String kind, String tx) {
    if (TX_COSTED.equals(kind) && !reviewing.contains(tx) || REVIEW_RESOLVED.equals(kind)) {
      release(tx);
    }
  }

  /**
   * Re-learn from a record that is already in a log: learn, then close, as it happened.
   *
   * @param record a decoded record
   */
  void replay(Map<String, Object> record) {
    String kind = String.valueOf(record.get("kind"));
    String tx = Objects.toString(record.get("tx"), null);
    learn(kind, tx, record.get("data"));
    close(kind, tx);
  }

  /**
   * The logs each open transaction belongs in.
   *
   * @return a copy of the routing
   */
  Map<String, List<String>> logsByTx() {
    return new HashMap<>(logsByTx);
  }

  /**
   * The transactions waiting for a review to be resolved.
   *
   * @return a copy of the set
   */
  Set<String> reviewing() {
    return new HashSet<>(reviewing);
  }

  private void release(String tx) {
    logsByTx.remove(tx);
    reviewing.remove(tx);
  }

  private static boolean isDecision(String kind) {
    return TX_RECEIVED.equals(kind) || TX_RESUMED.equals(kind);
  }

  private static Optional<List<String>> logsOf(Object data) {
    Object logs = child(child(data, "eligibility"), "logs");
    if (logs instanceof List<?> list) {
      return Optional.of(list.stream().map(item -> String.valueOf(item)).toList());
    }
    return Optional.empty();
  }

  private static Object child(Object node, String key) {
    return node instanceof Map<?, ?> map ? map.get(key) : null;
  }
}

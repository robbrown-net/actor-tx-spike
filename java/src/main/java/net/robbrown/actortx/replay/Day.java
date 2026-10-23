package net.robbrown.actortx.replay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.robbrown.actortx.audit.AuditLog;

/**
 * A loaded day: its records in order, grouped by transaction, and the head of the chain it was
 * verified to (empty if it was loaded without verification).
 *
 * @param records every record, in log order
 * @param byTx the records of each transaction, in log order
 * @param txOrder the transactions, in the order they arrived
 * @param head the verified head of the log, if it was verified
 */
public record Day(
    List<Map<String, Object>> records,
    Map<String, List<Map<String, Object>>> byTx,
    List<String> txOrder,
    Optional<AuditLog.Head> head) {

  /**
   * Build a day from already-decoded records (from a log, a test, or another source).
   *
   * @param records the records, in order
   * @param head the verified head, if any
   * @return the day
   */
  public static Day of(List<Map<String, Object>> records, Optional<AuditLog.Head> head) {
    Map<String, List<Map<String, Object>>> byTx = new LinkedHashMap<>();
    Set<String> order = new LinkedHashSet<>();
    for (Map<String, Object> record : records) {
      index(record, byTx, order);
    }
    return new Day(List.copyOf(records), byTx, new ArrayList<>(order), head);
  }

  private static void index(
      Map<String, Object> record, Map<String, List<Map<String, Object>>> byTx, Set<String> order) {
    Optional<String> tx = Optional.ofNullable(record.get("tx")).map(String::valueOf);
    tx.ifPresent(id -> byTx.computeIfAbsent(id, key -> new ArrayList<>()).add(record));
    if (Fields.is(record, "tx_received")) {
      tx.ifPresent(order::add);
    }
  }

  /**
   * One transaction, summarised.
   *
   * @param id the transaction
   * @return its summary, or empty if the day has no records of it
   */
  public Optional<TxSummary> tx(String id) {
    return Optional.ofNullable(byTx.get(id)).map(Tx::summarize);
  }

  /**
   * Every transaction, summarised, in arrival order.
   *
   * @return the summaries
   */
  public List<TxSummary> transactions() {
    return txOrder.stream().map(id -> Tx.summarize(byTx.get(id))).toList();
  }
}

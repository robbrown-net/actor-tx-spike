package net.robbrown.actortx.replay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Data residency, proven from the logs themselves. Each transaction records, in its tx_received,
 * the logs it must be written to. Given the regional logs loaded as days, this checks that every
 * transaction appears in exactly those logs: not in one where it must not be (a leak), and not
 * missing from one where it must be.
 */
public final class Residency {

  private Residency() {}

  /**
   * A transaction in a log it must not be in, or missing from one it must be in.
   *
   * @param rule which residency rule it breaks
   * @param tx the transaction
   * @param log the log concerned
   * @param detail what, in words
   */
  public record Violation(Rule rule, String tx, String log, String detail) {}

  /**
   * The result of checking residency.
   *
   * @param transactions how many distinct transactions were seen across the logs
   * @param violations every breach
   */
  public record Result(int transactions, List<Violation> violations) {

    /**
     * Did every transaction sit exactly where it must?
     *
     * @return true if there are no violations
     */
    public boolean ok() {
      return violations.isEmpty();
    }
  }

  /**
   * Check residency across the regional logs.
   *
   * @param days the logs, by name
   * @return the result
   */
  public static Result check(Map<String, Day> days) {
    Set<String> ids = new TreeSet<>();
    days.values().forEach(day -> ids.addAll(day.txOrder()));
    List<Violation> violations = new ArrayList<>();
    for (String id : ids) {
      violations.addAll(checkTx(id, days));
    }
    return new Result(ids.size(), violations);
  }

  /**
   * The logs that hold records of a transaction.
   *
   * @param days the logs, by name
   * @param id the transaction
   * @return the names, sorted
   */
  public static List<String> logsHolding(Map<String, Day> days, String id) {
    return days.entrySet().stream()
        .filter(entry -> entry.getValue().byTx().containsKey(id))
        .map(Map.Entry::getKey)
        .toList();
  }

  private static List<Violation> checkTx(String id, Map<String, Day> days) {
    List<String> present = logsHolding(days, id);
    List<String> expected = expectedLogs(id, days, present);
    List<Violation> leaks =
        present.stream()
            .filter(log -> !expected.contains(log))
            .map(
                log ->
                    new Violation(
                        Rule.RESIDENCY_LEAK,
                        id,
                        log,
                        "%s is recorded in %s, which it must not be (expected %s)"
                            .formatted(id, log, Fields.inspect(expected))))
            .toList();
    List<Violation> missing =
        expected.stream()
            .filter(log -> !present.contains(log))
            .map(
                log ->
                    new Violation(
                        Rule.RESIDENCY_MISSING,
                        id,
                        log,
                        "%s is missing from %s, where it must be recorded".formatted(id, log)))
            .toList();
    return Stream.concat(leaks.stream(), missing.stream()).toList();
  }

  /** The logs the transaction's own decision names, read from the first log that holds it. */
  private static List<String> expectedLogs(String id, Map<String, Day> days, List<String> present) {
    Optional<Map<String, Object>> received =
        present.stream()
            .map(log -> Tx.first(days.get(log).byTx().get(id), "tx_received"))
            .filter(record -> !record.isEmpty())
            .findFirst();
    return received
        .flatMap(record -> Optional.ofNullable(Fields.dig(record, "data", "eligibility", "logs")))
        .map(Fields::strings)
        .orElse(present);
  }
}

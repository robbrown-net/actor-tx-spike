package net.robbrown.actortx.service;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import net.robbrown.actortx.replay.Day;
import net.robbrown.actortx.replay.Replay;
import net.robbrown.actortx.replay.Residency;
import net.robbrown.actortx.replay.Rules;

/**
 * Replays the durable log: checks every recorded day against the rules the system promises (the
 * steps, the money, the order, the residency of each record) and says what, if anything, broke.
 */
public final class ReplayService {

  private final Optional<Path> directory;
  private final Supplier<Optional<String>> barrier;

  /**
   * A service over a log directory.
   *
   * @param directory the audit directory, or empty when nothing is written down
   * @param barrier makes everything announced so far durable before the log is read
   */
  public ReplayService(Optional<Path> directory, Supplier<Optional<String>> barrier) {
    this.directory = directory;
    this.barrier = barrier;
  }

  /** What the replay found, per log, and whether records stayed in their regions. */
  public Map<String, Object> verify() {
    if (directory.isEmpty()) {
      return Map.of("available", false);
    }
    barrier.get();
    Map<String, Day> days = Replay.loadDir(directory.get());
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("available", true);
    report.put(
        "logs", days.entrySet().stream().map(e -> logReport(e.getKey(), e.getValue())).toList());
    report.put("residency", residency(days));
    return report;
  }

  private static Map<String, Object> logReport(String name, Day day) {
    Rules.Verdict verdict = Rules.verify(day);
    Map<String, Object> log = new LinkedHashMap<>();
    log.put("log", name);
    log.put("records", verdict.records());
    log.put("transactions", verdict.transactions());
    log.put("ok", verdict.ok());
    log.put("violations", verdict.violations().stream().map(v -> violation(v)).toList());
    return log;
  }

  private static Map<String, Object> violation(Rules.Violation v) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("rule", v.rule().name().toLowerCase(java.util.Locale.ROOT));
    map.put("tx", v.tx());
    map.put("detail", v.detail());
    return map;
  }

  private static Map<String, Object> residency(Map<String, Day> days) {
    Residency.Result result = Residency.check(days);
    List<String> problems =
        result.violations().stream()
            .map(v -> v.tx() + " in " + v.log() + ": " + v.detail())
            .toList();
    return Map.of("transactions", result.transactions(), "ok", result.ok(), "violations", problems);
  }
}

package net.robbrown.actortx.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.robbrown.actortx.events.DurableSink;
import net.robbrown.actortx.events.Event;

/**
 * Regional audit logs: data residency for the record. Some jurisdictions require data to stay
 * in-country, so each region has its own hash-chained {@link AuditLog} (a file {@code
 * <dir>/<name>.log}), and each event goes to the logs its transaction's jurisdiction decision
 * names. Events with no transaction, and transactions the router has no routing for, go to {@code
 * GLOBAL}.
 *
 * <p>An event naming a log the router does not have is refused before anything is written: there is
 * nowhere lawful to record it, so the money must not move. The routing is rebuilt from the logs on
 * open, so a restarted router keeps sending open transactions to their own regions.
 */
public final class AuditRouter implements DurableSink, AutoCloseable {

  private static final System.Logger LOG = System.getLogger(AuditRouter.class.getName());

  private final Path dir;
  private final Map<String, AuditLog> logs;
  private final RoutingTable routing;

  private AuditRouter(Path dir, Map<String, AuditLog> logs, RoutingTable routing) {
    this.dir = dir;
    this.logs = logs;
    this.routing = routing;
  }

  /**
   * Open the regional logs of a directory, rebuilding the routing from them.
   *
   * @param dir the directory (created if missing)
   * @param logNames the regions; {@code GLOBAL} always exists
   * @param sync whether the logs make records durable (see {@link AuditLog#open})
   * @return the open router
   * @throws AuditException if a log is locked, broken or cannot be opened
   */
  public static AuditRouter open(Path dir, List<String> logNames, boolean sync) {
    return open(dir, logNames, sync, AuditLog.FSYNC);
  }

  static AuditRouter open(Path dir, List<String> logNames, boolean sync, AuditLog.Forcer forcer) {
    Unchecked.run(() -> Files.createDirectories(dir));
    Map<String, AuditLog> logs = new LinkedHashMap<>();
    try {
      for (String name : names(logNames)) {
        logs.put(name, AuditLog.open(dir.resolve(name + ".log"), sync, forcer));
      }
      return new AuditRouter(dir, logs, rebuild(dir, logs.keySet()));
    } catch (RuntimeException refused) {
      logs.values().forEach(AuditLog::close);
      throw refused;
    }
  }

  /**
   * Route an event to the logs its transaction belongs in. The decision that opens a transaction
   * teaches the routing before the event is routed.
   *
   * @param event what happened
   * @throws AuditException if the event names a log the router does not have, or a record cannot be
   *     written
   */
  @Override
  public synchronized void write(Event event) {
    routing.learn(event.kind(), event.txId(), event.data());
    List<AuditLog> targets =
        routing.targets(event.txId()).stream().map(name -> logNamed(name, event)).toList();
    targets.forEach(log -> log.write(event));
    routing.close(event.kind(), event.txId());
  }

  /**
   * Make every regional log durable, so that a failure in one is never hidden by another.
   *
   * @return empty if all are durable, otherwise the first failure
   */
  @Override
  public Optional<String> flush() {
    return flushAsync().join();
  }

  /**
   * Start the durability of every regional log at once, so their forces can share the disk's time.
   * The outcome is the first failure, in the order of the logs.
   *
   * @return the outcome, possibly still pending
   */
  @Override
  public synchronized CompletableFuture<Optional<String>> flushAsync() {
    List<CompletableFuture<Optional<String>>> results =
        logs.values().stream().map(AuditLog::flushAsync).toList();
    return CompletableFuture.allOf(results.toArray(CompletableFuture[]::new))
        .thenApply(
            ignored ->
                results.stream()
                    .map(CompletableFuture::join)
                    .flatMap(Optional::stream)
                    .findFirst());
  }

  /**
   * The path of every regional log, by name.
   *
   * @return the paths
   */
  public Map<String, Path> paths() {
    Map<String, Path> paths = new LinkedHashMap<>();
    logs.keySet().forEach(name -> paths.put(name, dir.resolve(name + ".log")));
    return Map.copyOf(paths);
  }

  /**
   * The head of every regional log, by name.
   *
   * @return the heads
   */
  public Map<String, AuditLog.Head> heads() {
    Map<String, AuditLog.Head> heads = new LinkedHashMap<>();
    logs.forEach((name, log) -> heads.put(name, log.head()));
    return Map.copyOf(heads);
  }

  /**
   * What the regional logs have done for durability, summed over every log.
   *
   * @return the figures
   */
  public synchronized AuditStats stats() {
    return logs.values().stream().map(AuditLog::stats).reduce(AuditStats.NONE, AuditStats::plus);
  }

  /** Close every regional log, releasing their locks, and log what the audit did. */
  @Override
  public synchronized void close() {
    logs.values().forEach(AuditLog::close);
    AuditStats stats = stats();
    LOG.log(
        System.Logger.Level.INFO,
        "audit closed: records={0} syncs={1} synced_records={2} records_per_sync={3}",
        stats.records(),
        stats.syncs(),
        stats.syncedRecords(),
        String.format("%.2f", stats.recordsPerSync()));
  }

  /** The routing table, for the router's own tests. */
  RoutingTable table() {
    return routing;
  }

  private AuditLog logNamed(String name, Event event) {
    AuditLog log = logs.get(name);
    if (log == null) {
      throw new AuditException(
          "no audit log named \""
              + name
              + "\" for "
              + event.txId()
              + ": refusing to record it elsewhere");
    }
    return log;
  }

  private static List<String> names(List<String> logNames) {
    return Stream.concat(logNames.stream(), Stream.of(RoutingTable.GLOBAL)).distinct().toList();
  }

  private static RoutingTable rebuild(Path dir, Set<String> names) {
    RoutingTable table = new RoutingTable();
    for (String name : names) {
      AuditChain.records(dir.resolve(name + ".log")).forEach(table::replay);
    }
    return table;
  }
}

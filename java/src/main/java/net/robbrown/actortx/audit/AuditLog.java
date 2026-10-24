package net.robbrown.actortx.audit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.robbrown.actortx.events.DurableSink;
import net.robbrown.actortx.events.Event;

/**
 * A durable, append-only, hash-chained audit log: the evidence trail of everything the system
 * narrates. One JSON object per line, each committing to the one before it, so editing, removing,
 * inserting or reordering any record breaks the chain and {@link #verify} says where. A chain
 * cannot show truncation of its tail: anchor {@link #head()} somewhere independent at intervals.
 *
 * <p>The log holds one writer at a time (a lock file) and refuses to open on top of a log whose
 * chain is broken. Free-text fields are scrubbed of payment card numbers before they reach the
 * disk.
 *
 * <p>Durability is group commit. {@link #write} appends a record to the file without forcing it.
 * {@link #flushAsync} is the write-ahead barrier: its future completes only once a force that
 * started after the barrier's records were written has returned successfully. One sync thread
 * performs the forces. Each force covers every record written before it started, so the barriers
 * that arrive while a force is running share the next one. Records nobody asks about are forced
 * within {@link #SYNC_EVERY_MS} of being written. A failed force fails every barrier waiting on it,
 * and the log refuses writes from then on.
 */
public final class AuditLog implements DurableSink, AutoCloseable {

  /** How long a dirty log waits for more records before it is made durable without being asked. */
  private static final long SYNC_EVERY_MS = 50;

  /** Makes a channel's data durable. A seam, so tests can make the disk fail or stall. */
  @FunctionalInterface
  interface Forcer {
    /**
     * Force the channel's data to the disk.
     *
     * @param channel the log
     * @throws IOException if the disk refuses
     */
    void force(FileChannel channel) throws IOException;
  }

  /** The production forcer: an fsync of the data. */
  static final Forcer FSYNC = channel -> channel.force(false);

  /**
   * How far a log goes: its record count and the hash of its last record.
   *
   * @param records the number of records
   * @param hash the hash of the last record (the genesis hash when empty)
   */
  public record Head(long records, String hash) {}

  /** What checking a whole log found. */
  public sealed interface Verification permits Verification.Intact, Verification.Broken {

    /**
     * The log is intact.
     *
     * @param head its last record
     */
    record Intact(Head head) implements Verification {}

    /**
     * The chain breaks at a record.
     *
     * @param atRecord the 1-based number of the first record that fails
     * @param reason why it fails: malformed, out_of_sequence, chain_broken or hash_mismatch
     */
    record Broken(long atRecord, String reason) implements Verification {}
  }

  /** A barrier waiting for a force to cover every record up to {@code target}. */
  private record Waiter(long target, CompletableFuture<Optional<String>> done) {}

  private final AuditLock lock;
  private final FileChannel channel;
  private final boolean sync;
  private final Forcer forcer;
  private final List<Waiter> waiters = new ArrayList<>();
  private long records;
  private String hash;
  private String failure;
  private boolean closed;
  private long syncs;
  private long syncedRecords;
  private Thread syncer;

  private AuditLog(AuditLock lock, Head head, FileChannel channel, boolean sync, Forcer forcer) {
    this.lock = lock;
    this.records = head.records();
    this.hash = head.hash();
    this.channel = channel;
    this.sync = sync;
    this.forcer = forcer;
  }

  /**
   * Open a log for writing: take its lock, verify the chain that is already there, and continue it.
   *
   * @param path the log file (created if missing)
   * @param sync whether barriers and the sync thread make records durable (fsync); off only for
   *     tests and throwaway runs
   * @return the open log
   * @throws AuditException if a live writer holds it, or its chain is broken
   */
  public static AuditLog open(Path path, boolean sync) {
    return open(path, sync, FSYNC);
  }

  static AuditLog open(Path path, boolean sync, Forcer forcer) {
    AuditLock lock = AuditLock.acquire(path);
    try {
      Head head = AuditChain.resume(path);
      FileChannel channel = Unchecked.get(() -> FileChannel.open(path, APPEND, CREATE, WRITE));
      AuditLog log = new AuditLog(lock, head, channel, sync, forcer);
      log.startSyncer();
      return log;
    } catch (RuntimeException refused) {
      lock.release();
      throw refused;
    }
  }

  /**
   * Check a whole log file: sequence, chain links and every hash. A missing file is empty and
   * intact.
   *
   * @param path the log
   * @return intact with its head, or broken at the first record that fails
   */
  public static Verification verify(Path path) {
    return AuditChain.verify(path);
  }

  /**
   * Append one event to the chain. Free-text fields are scrubbed first. The record is not durable
   * until a barrier or the sync thread forces it.
   *
   * @param event what happened
   * @throws AuditException if the record cannot be written, or the log has failed a sync
   */
  @Override
  public synchronized void write(Event event) {
    checkWritable();
    AuditChain.Sealed sealed = AuditChain.seal(head(), event);
    Unchecked.run(() -> channel.write(ByteBuffer.wrap(sealed.line().getBytes(UTF_8))));
    records++;
    hash = sealed.hash();
    if (sync && records == syncedRecords + 1) {
      notifyAll();
    }
  }

  private void checkWritable() {
    if (closed) {
      throw new AuditException("audit log is closed");
    }
    if (failure != null) {
      throw new AuditException("audit log refuses writes after a failed sync: " + failure);
    }
  }

  /**
   * Make everything written so far durable, and wait for it. See {@link #flushAsync}.
   *
   * @return empty if durable, otherwise why not
   */
  @Override
  public Optional<String> flush() {
    return flushAsync().join();
  }

  /**
   * The write-ahead barrier. The future completes with empty once every record written before this
   * call is on disk, which happens only after a successful force that started after the call.
   * Otherwise it completes with why not. A failed sync is sticky: the log refuses writes from then
   * on, because what the disk holds is no longer known.
   *
   * @return the outcome, possibly still pending
   */
  @Override
  public synchronized CompletableFuture<Optional<String>> flushAsync() {
    if (failure != null) {
      return CompletableFuture.completedFuture(Optional.of(failure));
    }
    if (!sync || records == syncedRecords) {
      return CompletableFuture.completedFuture(Optional.empty());
    }
    CompletableFuture<Optional<String>> done = new CompletableFuture<>();
    waiters.add(new Waiter(records, done));
    notifyAll();
    return done;
  }

  /**
   * The record count and the hash of the last record. Anchor this somewhere independent.
   *
   * @return the head
   */
  public synchronized Head head() {
    return new Head(records, hash);
  }

  /**
   * What the log has done for durability so far.
   *
   * @return the figures
   */
  public synchronized AuditStats stats() {
    return new AuditStats(records, syncs, syncedRecords);
  }

  /** Make everything durable, stop the sync thread, close the file and release the lock. */
  @Override
  public void close() {
    try {
      while (!sealed()) {
        flush();
      }
      Unchecked.run(channel::close);
    } finally {
      lock.release();
    }
  }

  /**
   * Refuse further writes once nothing is left to force. True when the log is durable or has
   * failed, so closing can finish; false while written records still await a force.
   */
  private synchronized boolean sealed() {
    if (failure != null || !sync || records == syncedRecords) {
      closed = true;
      notifyAll();
      return true;
    }
    return false;
  }

  /** Start the sync thread: the only place forces happen, so no caller ever waits on the disk. */
  private void startSyncer() {
    if (sync) {
      syncer = Thread.ofPlatform().name("audit-sync").daemon(true).start(this::syncLoop);
    }
  }

  /** The sync thread's loop. It exits once the log is closed and nothing is left to force. */
  private void syncLoop() {
    long covered = awaitWork();
    while (covered >= 0) {
      forceAndRelease(covered);
      covered = awaitWork();
    }
  }

  /**
   * Wait until there is something to force, then count the force and return how many records it
   * will cover. Returns -1 once the log is closed and nothing is left.
   */
  private synchronized long awaitWork() {
    while (!closed && !hasWork()) {
      park(0);
    }
    if (!hasWork()) {
      return -1;
    }
    awaitBatch();
    syncs++;
    return records;
  }

  /** Force the disk, then release the barriers the force covers, or fail them all. */
  private void forceAndRelease(long covered) {
    String error = null;
    try {
      Unchecked.run(() -> forcer.force(channel));
    } catch (AuditException refused) {
      error = refused.getMessage();
    }
    Optional<String> outcome = Optional.ofNullable(error);
    List<Waiter> released;
    synchronized (this) {
      if (outcome.isPresent()) {
        failure = error;
        released = release(Long.MAX_VALUE);
      } else {
        syncedRecords = covered;
        released = release(covered);
      }
    }
    released.forEach(waiter -> waiter.done().complete(outcome));
  }

  /** Takes the waiters whose records are covered, or all of them. Call holding the monitor. */
  private List<Waiter> release(long upTo) {
    List<Waiter> released = waiters.stream().filter(w -> w.target() <= upTo).toList();
    waiters.removeAll(released);
    return released;
  }

  /** Whether the sync thread has something to do. Call holding the monitor. */
  private boolean hasWork() {
    return failure == null && (!waiters.isEmpty() || records > syncedRecords);
  }

  /**
   * Let written records gather for up to {@link #SYNC_EVERY_MS} before a force, unless a barrier
   * arrives first. Call holding the monitor.
   */
  private void awaitBatch() {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SYNC_EVERY_MS);
    long left = deadline - System.nanoTime();
    while (!closed && waiters.isEmpty() && left > 0) {
      park(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
      left = deadline - System.nanoTime();
    }
  }

  /**
   * Wait on the monitor. An interrupt is absorbed: nothing interrupts the sync thread on purpose,
   * and a stray one must not stop it.
   */
  private void park(long millis) {
    try {
      wait(millis);
    } catch (InterruptedException stray) {
      // The exception has already cleared the flag, so the next wait blocks rather than spins.
      Thread.interrupted();
    }
  }

  /** The sync thread, for tests that need to disturb it. */
  Thread syncer() {
    return syncer;
  }
}

package net.robbrown.actortx.audit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
 * disk. Records are made durable together: {@link #flush} is the write-ahead barrier, and a timer
 * covers records nobody asks about.
 */
public final class AuditLog implements DurableSink, AutoCloseable {

  /** How long a dirty log waits before it is made durable without being asked (group commit). */
  private static final long SYNC_EVERY_MS = 50;

  /** Makes a channel's data durable. A seam, so tests can make the disk fail. */
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

  private final AuditLock lock;
  private final FileChannel channel;
  private final boolean sync;
  private final Forcer forcer;
  private final ScheduledExecutorService timer = newTimer();
  private long records;
  private String hash;
  private boolean dirty;
  private boolean timerArmed;
  private String failure;

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
   * @param sync whether flushes and the timer make records durable (fsync); off only for tests and
   *     throwaway runs
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
      return new AuditLog(lock, head, channel, sync, forcer);
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
   * Append one event to the chain. Free-text fields are scrubbed first.
   *
   * @param event what happened
   * @throws AuditException if the record cannot be written, or the log has failed a sync
   */
  @Override
  public synchronized void write(Event event) {
    if (failure != null) {
      throw new AuditException("audit log refuses writes after a failed sync: " + failure);
    }
    AuditChain.Sealed sealed = AuditChain.seal(head(), event);
    Unchecked.run(() -> channel.write(ByteBuffer.wrap(sealed.line().getBytes(UTF_8))));
    records++;
    hash = sealed.hash();
    dirty = true;
    if (sync) {
      armTimer();
    }
  }

  /**
   * Make everything written so far durable. A failed sync is sticky: the log refuses writes from
   * then on, because what the disk holds is no longer known.
   *
   * @return empty if durable, otherwise why not
   */
  @Override
  public synchronized Optional<String> flush() {
    if (failure == null) {
      try {
        syncIfDirty();
      } catch (AuditException refused) {
        failure = refused.getMessage();
      }
    }
    return Optional.ofNullable(failure);
  }

  /**
   * The record count and the hash of the last record. Anchor this somewhere independent.
   *
   * @return the head
   */
  public synchronized Head head() {
    return new Head(records, hash);
  }

  /** Flush, stop the timer, close the file and release the lock. */
  @Override
  public synchronized void close() {
    try {
      flush();
      timer.shutdownNow();
      Unchecked.run(channel::close);
    } finally {
      lock.release();
    }
  }

  private void armTimer() {
    if (!timerArmed) {
      timerArmed = true;
      timer.schedule(() -> onTimer(), SYNC_EVERY_MS, TimeUnit.MILLISECONDS);
    }
  }

  private synchronized void onTimer() {
    timerArmed = false;
    flush();
  }

  private void syncIfDirty() {
    if (sync && dirty) {
      Unchecked.run(() -> forcer.force(channel));
      dirty = false;
    }
  }

  private static ScheduledExecutorService newTimer() {
    return Executors.newSingleThreadScheduledExecutor(
        task -> {
          Thread thread = new Thread(task, "audit-sync");
          thread.setDaemon(true);
          return thread;
        });
  }
}

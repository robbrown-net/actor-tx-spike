package net.robbrown.actortx.audit;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * One writer per audit log. A hash chain has exactly one head, so two writers appending to one file
 * would break it for good. The writer takes {@code <log>.lock}, which names its OS process id.
 *
 * <p>A lock whose owner is gone is stale and is taken over (a crashed process never releases it):
 * the owner is gone if its process is not running, or if it is this very JVM and no writer in this
 * JVM holds the log any more. A lock whose owner is alive refuses the new writer.
 */
final class AuditLock {

  /** The locks this JVM holds right now. */
  private static final Set<Path> HELD = ConcurrentHashMap.newKeySet();

  private static final Pattern PID = Pattern.compile("\\d{1,18}");

  private final Path lock;

  private AuditLock(Path lock) {
    this.lock = lock;
  }

  /**
   * Take the lock for a log, or refuse.
   *
   * @param log the log file the lock guards
   * @return the held lock
   * @throws AuditException if a live writer holds it, or it cannot be taken
   */
  static AuditLock acquire(Path log) {
    Path target = log.toAbsolutePath().normalize();
    Path lock = target.resolveSibling(target.getFileName() + ".lock");
    Unchecked.run(() -> Files.createDirectories(target.getParent()));
    if (tryCreate(lock)) {
      HELD.add(lock);
      return new AuditLock(lock);
    }
    String owner = Unchecked.get(() -> Files.readString(lock));
    if (!stale(lock, owner)) {
      throw new AuditException("audit log " + target + " is held by writer " + owner.strip());
    }
    Unchecked.run(() -> Files.deleteIfExists(lock));
    return acquire(target);
  }

  /**
   * Release the lock if this JVM holds it and the file still names it. Releasing twice is harmless.
   */
  void release() {
    HELD.remove(lock);
    Unchecked.run(() -> deleteIfOwned(lock));
  }

  private static boolean tryCreate(Path lock) {
    return Unchecked.get(() -> createExclusively(lock));
  }

  /**
   * Publish the owner as the lock in one step: the owner is written to a staging file, which is
   * then hard-linked to the lock name. The link fails if the lock exists, and a reader never sees
   * the lock half-written (an empty lock would look stale and be taken over).
   */
  private static boolean createExclusively(Path lock) throws IOException {
    Path staged = Files.createTempFile(lock.getParent(), lock.getFileName().toString(), ".staging");
    try {
      Files.writeString(staged, ownPid());
      Files.createLink(lock, staged);
      return true;
    } catch (FileAlreadyExistsException taken) {
      return false;
    } finally {
      Files.deleteIfExists(staged);
    }
  }

  private static void deleteIfOwned(Path lock) throws IOException {
    if (Files.exists(lock) && ownPid().equals(Files.readString(lock))) {
      Files.delete(lock);
    }
  }

  private static boolean stale(Path lock, String owner) {
    String text = owner.strip();
    if (!PID.matcher(text).matches()) {
      return true;
    }
    long pid = Long.parseLong(text);
    if (pid == ProcessHandle.current().pid()) {
      return !HELD.contains(lock);
    }
    return ProcessHandle.of(pid).map(process -> !process.isAlive()).orElse(true);
  }

  private static String ownPid() {
    return Long.toString(ProcessHandle.current().pid());
  }
}

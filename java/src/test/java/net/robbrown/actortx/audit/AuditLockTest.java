package net.robbrown.actortx.audit;

import static net.robbrown.actortx.audit.AuditFixtures.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditLockTest {

  @TempDir Path dir;

  private Path path() {
    return dir.resolve("audit.log");
  }

  private Path lockFile() {
    return dir.resolve("audit.log.lock");
  }

  private static String ownPid() {
    return Long.toString(ProcessHandle.current().pid());
  }

  private AuditLog open() {
    return AuditLog.open(path(), false);
  }

  @Test
  void theLockNamesThisProcessAndIsReleased() throws IOException {
    AuditLock lock = AuditLock.acquire(path());
    assertThat(lockFile()).hasContent(ownPid());
    lock.release();
    assertThat(lockFile()).doesNotExist();
  }

  @Test
  void theLockIsPublishedWholeAndLeavesNoStagingFileBehind() throws IOException {
    AuditLock lock = AuditLock.acquire(path());
    try (Stream<Path> files = Files.list(dir)) {
      assertThat(files.map(f -> f.getFileName().toString())).noneMatch(n -> n.endsWith(".staging"));
    }
    assertThat(lockFile()).hasContent(ownPid());
    lock.release();
  }

  @Test
  void aSecondWriterOnALiveLogIsRefusedAndTheChainStaysIntact() {
    try (AuditLog first = open()) {
      first.write(event(1, "tx-1", "step_succeeded"));
      assertThatThrownBy(this::open)
          .isInstanceOf(AuditException.class)
          .hasMessageContaining("held by writer " + ownPid());
      first.write(event(2, "tx-1", "step_succeeded"));
      assertThat(AuditLog.verify(path()))
          .isInstanceOfSatisfying(
              AuditLog.Verification.Intact.class, i -> assertThat(i.head().records()).isEqualTo(2));
    }
  }

  @Test
  void stoppingTheWriterReleasesTheLockForTheNextOne() {
    try (AuditLog first = open()) {
      first.write(event(1, "tx-1", "step_succeeded"));
    }
    assertThat(lockFile()).doesNotExist();
    try (AuditLog second = open()) {
      second.write(event(2, "tx-1", "step_succeeded"));
    }
    assertThat(AuditLog.verify(path()))
        .isInstanceOfSatisfying(
            AuditLog.Verification.Intact.class, i -> assertThat(i.head().records()).isEqualTo(2));
  }

  @Test
  void aLockLeftBehindByACrashedWriterIsTakenOver() throws IOException {
    Files.writeString(lockFile(), ownPid());
    try (AuditLog log = open()) {
      log.write(event(1, "tx-1", "step_succeeded"));
    }
    assertThat(lockFile()).doesNotExist();
  }

  @Test
  void aLockNamingAProcessThatHasExitedIsTakenOver() throws Exception {
    Process done = new ProcessBuilder("true").start();
    done.waitFor();
    Files.writeString(lockFile(), Long.toString(done.pid()));
    try (AuditLog log = open()) {
      log.write(event(1, "tx-1", "step_succeeded"));
    }
    assertThat(lockFile()).doesNotExist();
  }

  @Test
  void aLockHeldByAnotherRunningProcessRefusesUs() throws Exception {
    Process other = new ProcessBuilder("sleep", "30").start();
    try {
      Files.writeString(lockFile(), Long.toString(other.pid()));
      assertThatThrownBy(this::open)
          .isInstanceOf(AuditException.class)
          .hasMessageContaining("held by writer " + other.pid());
    } finally {
      other.destroyForcibly();
      other.waitFor();
    }
  }

  @Test
  void anUnreadableOrEmptyLockIsStale() throws IOException {
    Files.writeString(lockFile(), "garbage");
    try (AuditLog log = open()) {
      log.write(event(1, "tx-1", "step_succeeded"));
    }
    Files.writeString(lockFile(), "  ");
    try (AuditLog log = open()) {
      log.write(event(2, "tx-1", "step_succeeded"));
    }
    assertThat(lockFile()).doesNotExist();
  }

  @Test
  void aLockThatIsADirectoryCannotBeTakenOrReadAsAnOwner() throws IOException {
    Files.createDirectories(lockFile());
    assertThatThrownBy(this::open).isInstanceOf(AuditException.class);
  }

  @Test
  void aLockInAMissingDirectoryCreatesTheDirectory() {
    AuditLock lock = AuditLock.acquire(dir.resolve("nested/deeper/audit.log"));
    assertThat(dir.resolve("nested/deeper/audit.log.lock")).exists();
    lock.release();
  }

  @Test
  void releasingALockAnotherWriterNowOwnsLeavesItAlone() throws IOException {
    AuditLock lock = AuditLock.acquire(path());
    Files.writeString(lockFile(), "424242");
    lock.release();
    assertThat(lockFile()).hasContent("424242");
  }

  @Test
  void releasingTwiceOrAfterTheFileIsGoneIsHarmless() throws IOException {
    AuditLock lock = AuditLock.acquire(path());
    lock.release();
    lock.release();
    AuditLock again = AuditLock.acquire(path());
    Files.delete(lockFile());
    assertThatCode(again::release).doesNotThrowAnyException();
  }
}

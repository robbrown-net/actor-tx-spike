package net.robbrown.actortx.audit;

import static net.robbrown.actortx.audit.AuditFixtures.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.robbrown.actortx.audit.AuditLog.Verification.Intact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The write-ahead barrier as group commit: what a barrier waits for, and what it shares. */
class GroupCommitTest {

  private static final long SECONDS = 10;

  @TempDir Path dir;

  private Path path() {
    return dir.resolve("audit.log");
  }

  private static void hold(CountDownLatch gate) {
    try {
      gate.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  @Test
  void aBarrierCompletesOnlyAfterAForceThatStartedAfterItsRecordsWereWritten() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicLong sizeAtForce = new AtomicLong(-1);
    AuditLog.Forcer forcer =
        channel -> {
          sizeAtForce.set(channel.size());
          entered.countDown();
          hold(release);
          AuditLog.FSYNC.force(channel);
        };
    try (AuditLog log = AuditLog.open(path(), true, forcer)) {
      log.write(event(1, "tx-1", "step_succeeded"));
      CompletableFuture<Optional<String>> barrier = log.flushAsync();
      entered.await(SECONDS, TimeUnit.SECONDS);
      assertThat(barrier).isNotDone();
      assertThat(sizeAtForce.get()).isPositive();
      release.countDown();
      assertThat(barrier.get(SECONDS, TimeUnit.SECONDS)).isEmpty();
      assertThat(log.stats()).isEqualTo(new AuditStats(1, 1, 1));
    }
  }

  @Test
  void aFailedForceFailsEveryBarrierWaitingOnIt() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger attempts = new AtomicInteger();
    AuditLog.Forcer forcer =
        channel -> {
          attempts.incrementAndGet();
          entered.countDown();
          hold(release);
          throw new IOException("disk gone");
        };
    try (AuditLog log = AuditLog.open(path(), true, forcer)) {
      log.write(event(1, "tx-1", "step_succeeded"));
      CompletableFuture<Optional<String>> first = log.flushAsync();
      entered.await(SECONDS, TimeUnit.SECONDS);
      log.write(event(2, "tx-1", "step_succeeded"));
      CompletableFuture<Optional<String>> second = log.flushAsync();
      release.countDown();
      assertThat(first.get(SECONDS, TimeUnit.SECONDS)).hasValueSatisfying(this::diskGone);
      assertThat(second.get(SECONDS, TimeUnit.SECONDS)).hasValueSatisfying(this::diskGone);
      assertThat(attempts).hasValue(1);
      assertThat(log.flush()).hasValueSatisfying(this::diskGone);
      assertThatThrownBy(() -> log.write(event(3, "tx-1", "step_succeeded")))
          .isInstanceOf(AuditException.class)
          .hasMessageContaining("refuses writes");
    }
  }

  private void diskGone(String reason) {
    assertThat(reason).contains("disk gone");
  }

  /** Each appender writes {@code each} records and waits for a barrier after every one. */
  private static void appendConcurrently(AuditLog log, int appenders, int each) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(appenders);
    try {
      List<Future<?>> done = new ArrayList<>();
      for (int t = 0; t < appenders; t++) {
        String tx = "tx-" + t;
        done.add(pool.submit(() -> appendAndWait(log, tx, each)));
      }
      for (Future<?> f : done) {
        f.get(SECONDS, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private static void appendAndWait(AuditLog log, String tx, int each) {
    for (int i = 1; i <= each; i++) {
      log.write(event(i, tx, "step_succeeded"));
      assertThat(log.flush()).isEmpty();
    }
  }

  @Test
  void concurrentAppendersShareForcesAndTheChainStillVerifies() throws Exception {
    AuditLog.Forcer slowDisk =
        channel -> {
          try {
            Thread.sleep(2);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
          }
        };
    int appenders = 8;
    int each = 25;
    try (AuditLog log = AuditLog.open(path(), true, slowDisk)) {
      appendConcurrently(log, appenders, each);
      AuditStats stats = log.stats();
      assertThat(stats.records()).isEqualTo(appenders * each);
      assertThat(stats.syncedRecords()).isEqualTo(stats.records());
      assertThat(stats.syncs()).isPositive().isLessThan(stats.records());
      assertThat(stats.recordsPerSync()).isGreaterThan(1.0);
    }
    assertThat(AuditLog.verify(path()))
        .isInstanceOfSatisfying(
            Intact.class, i -> assertThat(i.head().records()).isEqualTo(appenders * each));
  }

  @Test
  void aSyncThreadThatIsInterruptedStillCompletesBarriers() {
    try (AuditLog log = AuditLog.open(path(), true, channel -> {})) {
      log.syncer().interrupt();
      log.write(event(1, "tx-1", "step_succeeded"));
      assertThat(log.flush()).isEmpty();
      assertThat(log.stats().syncs()).isEqualTo(1);
    }
  }

  @Test
  void aLogOpenedWithSyncOffHasNoSyncThreadAndBarriersAreImmediate() {
    try (AuditLog log = AuditLog.open(path(), false)) {
      log.write(event(1, "tx-1", "step_succeeded"));
      assertThat(log.syncer()).isNull();
      assertThat(log.flushAsync()).isCompletedWithValue(Optional.empty());
      assertThat(log.stats()).isEqualTo(new AuditStats(1, 0, 0));
    }
  }

  @Test
  void theStatsOfAStoppedLogAreStillReadable() {
    AuditLog log = AuditLog.open(path(), true, channel -> {});
    log.write(event(1, "tx-1", "step_succeeded"));
    log.close();
    assertThat(log.stats()).isEqualTo(new AuditStats(1, 1, 1));
  }

  @Test
  void theRouterSumsItsLogsAndClosingItLogsWhatItDid() {
    AtomicInteger forces = new AtomicInteger();
    try (AuditRouter router =
        AuditRouter.open(dir, List.of("IN"), true, AuditFixtures.counting(forces))) {
      router.write(AuditFixtures.received(1, "t-in", "IN"));
      router.write(event(2, "t-in", "step_started"));
      assertThat(router.flush()).isEmpty();
      long written = router.heads().values().stream().mapToLong(AuditLog.Head::records).sum();
      AuditStats stats = router.stats();
      assertThat(stats.records()).isEqualTo(written);
      assertThat(stats.syncedRecords()).isEqualTo(written);
      assertThat(stats.syncs()).isEqualTo(forces.get()).isPositive();
    }
  }

  @Test
  void aRouterFlushesEveryLogEvenWhenTheFirstFails() {
    AtomicInteger attempts = new AtomicInteger();
    AuditLog.Forcer failing =
        channel -> {
          attempts.incrementAndGet();
          throw new IOException("disk gone");
        };
    try (AuditRouter router = AuditRouter.open(dir, List.of("IN", "US"), true, failing)) {
      router.write(AuditFixtures.received(1, "t-in", "IN", "US"));
      assertThat(router.flush()).hasValueSatisfying(this::diskGone);
      assertThat(attempts.get()).isEqualTo(2);
    }
  }

  @Test
  void theStatsRatioIsZeroBeforeAnyForceAndTheSumIsExact() {
    assertThat(new AuditStats(0, 0, 0).recordsPerSync()).isZero();
    assertThat(new AuditStats(4, 2, 4).recordsPerSync()).isEqualTo(2.0);
    assertThat(new AuditStats(1, 1, 1).plus(new AuditStats(2, 3, 2)))
        .isEqualTo(new AuditStats(3, 4, 3));
  }
}

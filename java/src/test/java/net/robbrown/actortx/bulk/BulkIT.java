package net.robbrown.actortx.bulk;

import static net.robbrown.actortx.bulk.PlanTest.params;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.robbrown.actortx.service.PaymentCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A batch on real threads with the real clock and real pauses, against a submitter that answers
 * like a busy system: payments end a few milliseconds later on their own threads, some fail, and
 * some are refused as busy.
 */
class BulkIT {

  private static final Duration PATIENCE = Duration.ofSeconds(20);

  private RecordingEvents events;

  @BeforeEach
  void setUp() {
    events = new RecordingEvents();
  }

  private BulkRunner runner(Submitter submitter) {
    return new BulkRunner(
        submitter, Clock.systemUTC(), Sleeper.SYSTEM, new SplittableRandom(5), events);
  }

  private static Progress finished(BulkRunner runner) {
    await().atMost(PATIENCE).until(() -> runner.snapshot().status() == Progress.Status.FINISHED);
    return runner.snapshot();
  }

  @Test
  void aBatchSendsExactlyTheCountAndCountsHowEachPaymentEnded() {
    AsyncSubmitter submitter = new AsyncSubmitter(0, 2);
    BulkRunner runner = runner(submitter);

    runner.start(
        params(
            "count",
            40,
            "interval_ms",
            0,
            "mode",
            "random",
            "seed",
            7,
            "min_amount",
            10,
            "max_amount",
            20));
    Progress progress = finished(runner);

    assertThat(progress.submitted()).isEqualTo(40);
    assertThat(submitter.commands()).hasSize(40);
    assertThat(progress.completed() + progress.failed() + progress.needsReview()).isEqualTo(40);
    assertThat(progress.inFlight()).isZero();
    assertThat(progress.errors()).isZero();
    assertThat(progress.failed()).isPositive();
    assertThat(progress.completed()).isPositive();
    assertThat(events.kinds()).containsExactly("bulk_started", "bulk_finished");
  }

  @Test
  void failuresAreGroupedByTheirShapeWithTheNumbersTakenOut() {
    BulkRunner runner = runner(new AsyncSubmitter(0, 1));

    runner.start(params("count", 30, "interval_ms", 0, "mode", "random", "seed", 2));
    Progress progress = finished(runner);

    assertThat(progress.reasons()).isNotEmpty();
    assertThat(progress.reasons().get(0).text()).isEqualTo("quote N expired");
    assertThat(progress.reasons())
        .allSatisfy(reason -> assertThat(reason.text()).doesNotContainPattern("[0-9]"));
  }

  @Test
  void thePauseBetweenSubmissionsIsReallyHonoured() {
    // payments take about as long as a provider call, so the batch also waits for the last one
    BulkRunner runner = runner(new AsyncSubmitter(0, 60));

    runner.start(params("count", 5, "interval_ms", 40, "min_amount", 10, "max_amount", 20));
    Progress progress = finished(runner);

    assertThat(progress.elapsedMs()).isGreaterThanOrEqualTo(160);
    assertThat(progress.perSecond()).isLessThan(25);
  }

  @Test
  void aBusySystemIsRetriedAndNothingIsLost() {
    AsyncSubmitter submitter = new AsyncSubmitter(3, 2);
    BulkRunner runner = runner(submitter);

    runner.start(params("count", 12, "interval_ms", 0, "fixed", Map.of("instrument", "spot")));
    Progress progress = finished(runner);

    assertThat(progress.submitted()).isEqualTo(12);
    assertThat(progress.throttled()).isPositive();
    assertThat(progress.completed()).isEqualTo(12);
    assertThat(submitter.commands()).hasSize(12 + progress.throttled());
  }

  @Test
  void theSendersOwnCapKeepsItFromRunningTooManyAtOnce() {
    AsyncSubmitter submitter = new AsyncSubmitter(0, 20);
    BulkRunner runner = runner(submitter);

    runner.start(params("count", 20, "interval_ms", 0, "max_in_flight", 2));
    Progress progress = finished(runner);

    assertThat(progress.submitted()).isEqualTo(20);
    assertThat(progress.completed() + progress.failed() + progress.needsReview()).isEqualTo(20);
    assertThat(progress.waited()).isPositive();
    assertThat(progress.throttled()).isZero();
    assertThat(submitter.peak()).isLessThanOrEqualTo(2);
  }

  @Test
  void stoppingSendsNoMoreAndWhatWasSentStillEnds() {
    BulkRunner runner = runner(new AsyncSubmitter(0, 5));

    runner.start(params("count", 500, "interval_ms", 20));
    await().atMost(PATIENCE).until(() -> runner.snapshot().submitted() >= 3);
    assertThat(runner.stop()).isTrue();

    assertThat(runner.snapshot().status()).isEqualTo(Progress.Status.STOPPED);
    assertThat(runner.snapshot().submitted()).isLessThan(500);
    await().atMost(PATIENCE).until(() -> runner.snapshot().inFlight() == 0);
    int sent = runner.snapshot().submitted();
    await()
        .during(Duration.ofMillis(100))
        .atMost(PATIENCE)
        .until(() -> runner.snapshot().submitted() == sent);
    assertThat(runner.snapshot().completed() + runner.snapshot().failed()).isEqualTo(sent);
  }

  @Test
  void aSeededRandomBatchSendsTheSameTransactionsEveryTime() {
    Map<String, Object> plan = params("count", 15, "interval_ms", 0, "mode", "random", "seed", 99);
    AsyncSubmitter first = new AsyncSubmitter(0, 1);
    AsyncSubmitter second = new AsyncSubmitter(0, 1);

    finished(runnerStarted(first, plan));
    finished(runnerStarted(second, plan));

    assertThat(second.commands()).isEqualTo(first.commands());
  }

  private BulkRunner runnerStarted(Submitter submitter, Map<String, Object> plan) {
    BulkRunner runner = runner(submitter);
    runner.start(plan);
    return runner;
  }

  /**
   * Accepts every payment and ends it after a few milliseconds on the common pool. Every {@code
   * busyEvery}th call is refused as busy (0 for never). A payment on a forward instrument fails;
   * the rest complete.
   */
  private static final class AsyncSubmitter implements Submitter {

    private final int busyEvery;
    private final long delayMs;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger open = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();
    private final List<PaymentCommand> commands = new CopyOnWriteArrayList<>();

    AsyncSubmitter(int busyEvery, long delayMs) {
      this.busyEvery = busyEvery;
      this.delayMs = delayMs;
    }

    @Override
    public Submission submit(PaymentCommand command) {
      commands.add(command);
      int call = calls.incrementAndGet();
      if (busyEvery > 0 && call % busyEvery == 0) {
        return new Submission.Busy(open.get(), 2);
      }
      peak.accumulateAndGet(open.incrementAndGet(), Math::max);
      return new Submission.Accepted("tx-" + call, end(command));
    }

    /** Ends later on the common pool; the open count drops before the tally hears of the ending. */
    private CompletableFuture<Ending> end(PaymentCommand command) {
      Executor later = CompletableFuture.delayedExecutor(delayMs, TimeUnit.MILLISECONDS);
      CompletableFuture<Ending> ending =
          CompletableFuture.supplyAsync(() -> outcome(command), later);
      ending.whenComplete((done, failure) -> open.decrementAndGet());
      return ending;
    }

    private static Ending outcome(PaymentCommand command) {
      return "forward".equals(command.instrument())
          ? new Ending(Ending.Status.FAILED, "quote " + command.amount() + " expired")
          : new Ending(Ending.Status.COMPLETED, null);
    }

    List<PaymentCommand> commands() {
      return List.copyOf(commands);
    }

    int peak() {
      return peak.get();
    }
  }
}

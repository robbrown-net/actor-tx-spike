package net.robbrown.actortx.bulk;

import static net.robbrown.actortx.bulk.PlanTest.params;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.SplittableRandom;
import net.robbrown.actortx.service.InvalidInputException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BulkRunnerTest {

  private static final Ending DONE = new Ending(Ending.Status.COMPLETED, null);

  private ManualClock clock;
  private Pauses pauses;
  private RecordingEvents events;
  private StubSubmitter submitter;
  private BulkRunner runner;

  @BeforeEach
  void setUp() {
    clock = new ManualClock(0);
    pauses = new Pauses(clock);
    events = new RecordingEvents();
    submitter = new StubSubmitter();
    runner = new BulkRunner(submitter, clock, pauses, new SplittableRandom(7), events);
  }

  @Test
  void beforeAnyBatchTheRunnerIsIdle() {
    assertThat(runner.snapshot().status()).isEqualTo(Progress.Status.IDLE);
    assertThat(runner.snapshot().plan()).isNull();
    assertThat(runner.snapshot().recent()).isEmpty();
    assertThat(runner.stop()).isFalse();
    runner.clear();
  }

  @Test
  void aBatchRunsToFinishAndCountsEachEnding() {
    submitter.completingWith(DONE);

    String described = runner.start(params("count", 5, "interval_ms", 0));

    await().until(() -> events.kinds().contains("bulk_finished"));
    Progress progress = runner.snapshot();
    assertThat(progress.status()).isEqualTo(Progress.Status.FINISHED);
    assertThat(progress.plan()).isEqualTo(described);
    assertThat(progress.count()).isEqualTo(5);
    assertThat(progress.submitted()).isEqualTo(5);
    assertThat(progress.completed()).isEqualTo(5);
    assertThat(progress.inFlight()).isZero();
    assertThat(progress.errors()).isZero();
    assertThat(progress.recent()).hasSize(5);
    assertThat(events.kinds()).containsExactly("bulk_started", "bulk_finished");
    assertThat(events.all().get(1).data())
        .containsEntry("status", "finished")
        .containsEntry("submitted", 5);
  }

  @Test
  void aBatchStartIsAnnouncedWithItsPlan() {
    submitter.completingWith(DONE);

    runner.start(params("count", 2, "interval_ms", 0, "seed", 3));

    await().until(() -> events.kinds().contains("bulk_finished"));
    RecordingEvents.Emitted started = events.all().get(0);
    assertThat(started.message()).startsWith("operator: bulk batch started: 2 ×");
    assertThat(started.data()).containsKey("plan");
  }

  @Test
  void anInvalidPlanIsRefusedWithEveryErrorAndNothingStarts() {
    assertThatThrownBy(() -> runner.start(params("count", 0, "min_amount", "abc")))
        .isInstanceOf(InvalidInputException.class)
        .satisfies(error -> assertThat(((InvalidInputException) error).errors()).hasSize(2));
    assertThat(runner.snapshot().status()).isEqualTo(Progress.Status.IDLE);
    assertThat(events.kinds()).isEmpty();
  }

  @Test
  void aSecondBatchIsRefusedWhileOneRuns() {
    runner.start(params("count", 2, "interval_ms", 0));
    await().until(() -> runner.snapshot().status() == Progress.Status.DRAINING);

    assertThatThrownBy(() -> runner.start(params("count", 1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("a batch is already running: stop it first");

    submitter.endAll(Ending.Status.COMPLETED);
    await().until(() -> runner.snapshot().status() == Progress.Status.FINISHED);
  }

  @Test
  void clearIsRefusedWhileRunningAndIdlesTheRunnerAfterwards() {
    runner.start(params("count", 2, "interval_ms", 0));
    await().until(() -> runner.snapshot().status() == Progress.Status.DRAINING);

    assertThatThrownBy(runner::clear)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("stop the batch first");

    assertThat(runner.stop()).isTrue();
    submitter.endAll(Ending.Status.COMPLETED);
    await().until(() -> runner.snapshot().inFlight() == 0);
    runner.clear();
    assertThat(runner.snapshot().status()).isEqualTo(Progress.Status.IDLE);
  }

  @Test
  void stoppingSendsNoMoreButWhatWasSentStillEndsAndIsCounted() {
    BulkRunner slow =
        new BulkRunner(submitter, clock, Sleeper.SYSTEM, new SplittableRandom(7), events);

    slow.start(params("count", 5, "interval_ms", 60_000));
    await().until(() -> slow.snapshot().submitted() == 1);

    assertThat(slow.stop()).isTrue();
    assertThat(slow.snapshot().status()).isEqualTo(Progress.Status.STOPPED);
    assertThat(slow.snapshot().submitted()).isEqualTo(1);

    submitter.endAll(Ending.Status.COMPLETED);
    await().until(() -> slow.snapshot().inFlight() == 0);
    assertThat(slow.snapshot().status()).isEqualTo(Progress.Status.STOPPED);
    assertThat(slow.snapshot().completed()).isEqualTo(1);
    assertThat(events.all().get(events.all().size() - 1).data()).containsEntry("status", "stopped");
  }

  @Test
  void stoppingWhenNothingRunsChangesNothing() {
    submitter.completingWith(DONE);
    runner.start(params("count", 1, "interval_ms", 0));
    await().until(() -> runner.snapshot().status() == Progress.Status.FINISHED);

    assertThat(runner.stop()).isFalse();
    assertThat(runner.snapshot().status()).isEqualTo(Progress.Status.FINISHED);
    runner.clear();
    assertThat(runner.snapshot().status()).isEqualTo(Progress.Status.IDLE);
  }

  @Test
  void aFinishedBatchMakesWayForAnotherWithoutClearing() {
    submitter.completingWith(DONE);
    runner.start(params("count", 1, "interval_ms", 0));
    await().until(() -> runner.snapshot().status() == Progress.Status.FINISHED);

    runner.start(params("count", 2, "interval_ms", 0));

    await().until(() -> runner.snapshot().status() == Progress.Status.FINISHED);
    assertThat(runner.snapshot().submitted()).isEqualTo(2);
  }
}

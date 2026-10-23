package net.robbrown.actortx.bulk;

import static net.robbrown.actortx.bulk.PlanTest.params;
import static net.robbrown.actortx.bulk.PlanTest.valid;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.SplittableRandom;
import net.robbrown.actortx.service.PaymentCommand;
import org.junit.jupiter.api.Test;

class SenderTest {

  private static final Ending DONE = new Ending(Ending.Status.COMPLETED, null);

  private final ManualClock clock = new ManualClock(0);
  private final Pauses pauses = new Pauses(clock);

  private Sender sender(Plan plan, Submitter submitter, Tally tally, Sleeper sleeper) {
    return new Sender(
        plan, new CommandGenerator(plan, new SplittableRandom(3)), submitter, tally, sleeper);
  }

  @Test
  void itSendsTheCountPausingBetweenSubmissionsButNotAfterTheLast() {
    Plan plan = valid(params("count", 3, "interval_ms", 40));
    StubSubmitter submitter = new StubSubmitter().completingWith(DONE);
    Tally tally = new Tally(clock, plan);

    sender(plan, submitter, tally, pauses).run();

    assertThat(submitter.commands()).hasSize(3);
    assertThat(pauses.pauses()).containsExactly(40L, 40L);
    assertThat(tally.snapshot().submitted()).isEqualTo(3);
  }

  @Test
  void anInvalidCommandCountsAsSentAndAsAnError() {
    Plan plan = valid(params("count", 2, "interval_ms", 0));
    StubSubmitter submitter =
        new StubSubmitter().script(new Submission.Invalid(List.of("amount: nope")));
    Tally tally = new Tally(clock, plan);

    sender(plan, submitter, tally, pauses).run();

    assertThat(tally.snapshot().submitted()).isEqualTo(2);
    assertThat(tally.snapshot().errors()).isEqualTo(1);
    assertThat(tally.snapshot().inFlight()).isEqualTo(1);
  }

  @Test
  void aBusyCommandIsSentAgainAfterTheSystemsPause() {
    Plan plan = valid(params("count", 1, "interval_ms", 40));
    StubSubmitter submitter = new StubSubmitter().script(new Submission.Busy(3, 1000));
    submitter.completingWith(DONE);
    Tally tally = new Tally(clock, plan);

    sender(plan, submitter, tally, pauses).run();

    List<PaymentCommand> commands = submitter.commands();
    assertThat(commands).hasSize(2);
    assertThat(commands.get(1)).isEqualTo(commands.get(0));
    assertThat(pauses.pauses()).containsExactly(1000L);
    assertThat(tally.snapshot().throttled()).isEqualTo(1);
    assertThat(tally.snapshot().submitted()).isEqualTo(1);
  }

  @Test
  void aBusyPauseIsNeverShorterThanTheInterval() {
    Plan plan = valid(params("count", 1, "interval_ms", 40));
    StubSubmitter submitter = new StubSubmitter().script(new Submission.Busy(3, 10));
    submitter.completingWith(DONE);

    sender(plan, submitter, new Tally(clock, plan), pauses).run();

    assertThat(pauses.pauses()).containsExactly(40L);
  }

  @Test
  void aSenderAtItsOwnCapWaitsUntilAnEndingFreesAPlace() {
    Plan plan = valid(params("count", 2, "interval_ms", 0, "max_in_flight", 1));
    StubSubmitter submitter = new StubSubmitter();
    Tally tally = new Tally(clock, plan);
    Sender sender = sender(plan, submitter, tally, pauses);

    Thread.ofVirtual().start(sender::run);
    await().until(() -> tally.snapshot().waited() > 0 && tally.snapshot().submitted() == 1);
    submitter.endAll(Ending.Status.COMPLETED);
    await().until(() -> tally.sendingDone());
    submitter.endAll(Ending.Status.COMPLETED);

    assertThat(tally.snapshot().submitted()).isEqualTo(2);
    assertThat(tally.snapshot().waited()).isPositive();
  }

  @Test
  void aSenderThatIsNotRunningSendsNothing() {
    Plan plan = valid(params("count", 3));
    StubSubmitter submitter = new StubSubmitter().completingWith(DONE);
    Tally tally = new Tally(clock, plan);
    tally.stop();

    sender(plan, submitter, tally, pauses).run();

    assertThat(submitter.commands()).isEmpty();
  }

  @Test
  void anInterruptedPauseEndsTheSenderQuietlyAndRestoresTheFlag() {
    Plan plan = valid(params("count", 3, "interval_ms", 10));
    StubSubmitter submitter = new StubSubmitter().completingWith(DONE);
    Tally tally = new Tally(clock, plan);
    Sleeper interrupted =
        millis -> {
          throw new InterruptedException("stopped");
        };

    sender(plan, submitter, tally, interrupted).run();

    assertThat(Thread.currentThread().isInterrupted()).isTrue();
    assertThat(tally.snapshot().submitted()).isEqualTo(1);
    Thread.interrupted();
  }
}

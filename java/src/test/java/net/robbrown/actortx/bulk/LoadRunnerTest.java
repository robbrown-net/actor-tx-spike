package net.robbrown.actortx.bulk;

import static net.robbrown.actortx.bulk.PlanTest.params;
import static net.robbrown.actortx.bulk.PlanTest.valid;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class LoadRunnerTest {

  private final ManualClock clock = new ManualClock(0);
  private final Pauses pauses = new Pauses(clock);

  @Test
  void itSendsEverythingAndReportsHowItWentUnderTheLabel() {
    StubSubmitter submitter =
        new StubSubmitter().completingWith(new Ending(Ending.Status.COMPLETED, null));
    LoadRunner runner = new LoadRunner(submitter, clock, pauses, new SplittableRandom(1));

    LoadReport report = runner.measure("none", valid(params("count", 4, "interval_ms", 10)));

    assertThat(report.label()).isEqualTo("none");
    assertThat(report.sent()).isEqualTo(4);
    assertThat(report.completed()).isEqualTo(4);
    assertThat(report.failed()).isZero();
    assertThat(report.invalid()).isZero();
    assertThat(report.throttled()).isZero();
    assertThat(report.latency()).isEqualTo(new Percentiles(0, 0, 0, 0));
    assertThat(pauses.pauses()).containsExactly(10L, 10L, 10L);
  }

  @Test
  void busyAnswersAreRetriedAndInvalidOnesCounted() {
    StubSubmitter submitter =
        new StubSubmitter()
            .script(new Submission.Busy(1, 20), new Submission.Invalid(java.util.List.of("bad")))
            .completingWith(new Ending(Ending.Status.COMPLETED, null));
    LoadRunner runner = new LoadRunner(submitter, clock, pauses, new SplittableRandom(1));

    LoadReport report = runner.measure("100", valid(params("count", 2)));

    assertThat(report.label()).isEqualTo("100");
    assertThat(report.throttled()).isEqualTo(1);
    assertThat(report.invalid()).isEqualTo(1);
    assertThat(report.sent()).isEqualTo(2);
    assertThat(report.completed()).isEqualTo(1);
  }
}

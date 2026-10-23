package net.robbrown.actortx.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class LoadReportTest {

  private static Progress finished(long elapsedMs) {
    return new Progress(
        Progress.Status.FINISHED,
        "13 × 1.00 to 2.00",
        13,
        13,
        0,
        0,
        0,
        0,
        10,
        2,
        1,
        elapsedMs,
        0.0,
        List.of(),
        List.of(),
        new Percentiles(1, 2, 3, 4));
  }

  @Test
  void theReportCarriesTheCountsAndTheRatesOfEndings() {
    LoadReport report = LoadReport.of("l", finished(2_500));

    assertThat(report.sent()).isEqualTo(13);
    assertThat(report.completed()).isEqualTo(10);
    assertThat(report.failed()).isEqualTo(2);
    assertThat(report.needsReview()).isEqualTo(1);
    assertThat(report.seconds()).isEqualTo(2.5);
    assertThat(report.endedPerSecond()).isEqualTo(5.2);
    assertThat(report.latency()).isEqualTo(new Percentiles(1, 2, 3, 4));
  }

  @Test
  void aRunTooQuickToMeasureIsRatedOverATenthOfASecond() {
    LoadReport report = LoadReport.of("l", finished(0));

    assertThat(report.seconds()).isZero();
    assertThat(report.endedPerSecond()).isEqualTo(130.0);
  }

  @Test
  void theTableHasAHeadingLineAndOneLinePerRun() {
    String text = LoadReport.table(List.of(LoadReport.of("l", finished(2_500))));

    List<String> lines = text.lines().toList();
    assertThat(lines).hasSize(2);
    assertThat(lines.get(0)).startsWith(" limit  ").contains("ended/s").contains("max ms");
    assertThat(lines.get(1).trim().split("\\s+"))
        .containsExactly("l", "13", "0", "10", "2", "1", "0", "2.5", "5.2", "1", "2", "3", "4");
  }

  @Test
  void anEmptyTableIsJustTheHeading() {
    assertThat(LoadReport.table(List.of()).lines().toList()).hasSize(1);
  }
}

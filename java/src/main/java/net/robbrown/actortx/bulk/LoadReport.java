package net.robbrown.actortx.bulk;

import java.util.List;

/**
 * How one load run went: how many were sent and how each ended, how often the system was busy, the
 * time taken and the latency percentiles. Pure data; {@link #table} renders a set of runs as the
 * text the load tool prints (the same columns as {@code docs/quality/LOAD_RESULTS.md}).
 *
 * @param label what the run was measured with, e.g. an admission limit
 * @param sent how many were submitted
 * @param invalid how many were refused as invalid
 * @param completed how many completed
 * @param failed how many failed
 * @param needsReview how many need a person to look at them
 * @param throttled how many times the system was busy and the sender backed off
 * @param seconds the run's duration, one decimal place
 * @param endedPerSecond payments ended per second, one decimal place
 * @param latency how long the payments took to end
 */
public record LoadReport(
    String label,
    int sent,
    int invalid,
    int completed,
    int failed,
    int needsReview,
    int throttled,
    double seconds,
    double endedPerSecond,
    Percentiles latency) {

  private static final List<String> HEADINGS =
      List.of(
          "limit",
          "sent",
          "invalid",
          "completed",
          "failed",
          "review",
          "throttled",
          "seconds",
          "ended/s",
          "p50 ms",
          "p90 ms",
          "p99 ms",
          "max ms");
  private static final int[] WIDTHS = {6, 5, 7, 10, 7, 7, 10, 8, 8, 7, 7, 7, 7};

  /** The report of a run whose progress has ended. */
  public static LoadReport of(String label, Progress progress) {
    int ended = progress.completed() + progress.failed() + progress.needsReview();
    double seconds = Progress.round1(progress.elapsedMs() / 1000.0);
    return new LoadReport(
        label,
        progress.submitted(),
        progress.errors(),
        progress.completed(),
        progress.failed(),
        progress.needsReview(),
        progress.throttled(),
        seconds,
        Progress.round1(ended / Math.max(seconds, 0.1)),
        progress.latency());
  }

  /** The runs as an aligned text table, a heading line first. */
  public static String table(List<LoadReport> reports) {
    StringBuilder text = new StringBuilder(line(HEADINGS)).append('\n');
    reports.forEach(report -> text.append(line(report.cells())).append('\n'));
    return text.toString();
  }

  private List<String> cells() {
    return List.of(
        label,
        String.valueOf(sent),
        String.valueOf(invalid),
        String.valueOf(completed),
        String.valueOf(failed),
        String.valueOf(needsReview),
        String.valueOf(throttled),
        String.valueOf(seconds),
        String.valueOf(endedPerSecond),
        String.valueOf(latency.p50()),
        String.valueOf(latency.p90()),
        String.valueOf(latency.p99()),
        String.valueOf(latency.max()));
  }

  private static String line(List<String> cells) {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < cells.size(); i++) {
      if (i > 0) {
        text.append("  ");
      }
      text.append(String.format("%" + WIDTHS[i] + "s", cells.get(i)));
    }
    return text.toString();
  }
}

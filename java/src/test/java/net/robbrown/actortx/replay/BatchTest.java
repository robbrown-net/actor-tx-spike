package net.robbrown.actortx.replay;

import static net.robbrown.actortx.replay.ReplayFixtures.m;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Bulk batches read back from their records: the plan, when they ran, and how they ended. */
class BatchTest {

  private static final Map<String, Object> PLAN =
      m(
          "count",
          5,
          "min_minor",
          100,
          "max_minor",
          2_000,
          "interval_ms",
          50,
          "mode",
          "random",
          "seed",
          9);

  private static Map<String, Object> rec(long n, String kind, Map<String, Object> data) {
    return m(
        "n",
        n,
        "seq",
        n,
        "at",
        n * 10,
        "tx",
        null,
        "actor",
        "operator",
        "kind",
        kind,
        "data",
        data);
  }

  private static Map<String, Object> finished(String status, int submitted, int completed) {
    return m(
        "status", status,
        "submitted", submitted,
        "completed", completed,
        "failed", 0,
        "needs_review", 0);
  }

  @Test
  void eachBatchIsReadBackWithItsPlanAndHowItEnded() {
    List<Map<String, Object>> records = batchRecords();
    List<Batch> batches = Batch.of(records);
    assertThat(batches).hasSize(2);
    Batch first = batches.get(0);
    assertThat(first.plan()).containsEntry("count", 5);
    assertThat(first.startedAtMs()).isEqualTo(10L);
    assertThat(first.endedAtMs()).isEqualTo(20L);
    assertThat(first.result()).containsEntry("completed", 4);
    Batch second = batches.get(1);
    assertThat(second.plan()).containsEntry("count", 8);
    assertThat(second.result()).containsEntry("status", "stopped");
    assertThat(Replay.batches(Replay.fromRecords(records))).isEqualTo(batches);
    assertThat(Batch.of(List.of())).isEmpty();
  }

  private static List<Map<String, Object>> batchRecords() {
    return List.of(
        rec(0, "bulk_finished", m("status", "stopped")),
        rec(1, "bulk_started", m("plan", PLAN)),
        rec(2, "bulk_finished", finished("finished", 5, 4)),
        rec(3, "bulk_started", m("plan", m("count", 8))),
        rec(4, "bulk_finished", finished("stopped", 2, 2)),
        rec(5, "bulk_finished", m("status", "stopped")));
  }

  @Test
  void aBatchWithNoEndIsUnfinishedAndTheStateAtAMomentCountsTheBatchesSoFar() {
    List<Map<String, Object>> records =
        List.of(
            rec(1, "bulk_started", m("plan", PLAN)),
            rec(2, "bulk_finished", finished("finished", 5, 5)),
            rec(3, "bulk_started", m("plan", PLAN)));
    Day day = Replay.fromRecords(records);

    List<Batch> batches = Replay.batches(day);
    assertThat(batches.get(0).result()).containsEntry("status", "finished");
    assertThat(batches.get(1).endedAtMs()).isNull();
    assertThat(batches.get(1).result()).isEmpty();

    assertThat(Replay.stateAt(day, Position.n(1)).batches()).hasSize(1);
    assertThat(Report.state(Replay.stateAt(day, Position.n(3)))).contains("bulk batches: 2");

    String text = Report.batches(batches);
    assertThat(text)
        .contains("5 x 1.00 to 20.00, 50 ms apart, random, seed 9 -> finished: sent 5, completed 5")
        .contains("no end recorded");
    assertThat(Report.batches(List.of())).isEqualTo("no bulk batches recorded");
  }
}

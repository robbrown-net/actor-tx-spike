package net.robbrown.actortx.replay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A bulk batch as recorded: its plan (count, amount range, pause, mode, seed, fixed settings), when
 * it started and ended, and how it ended. A batch with no end record was still running, or the node
 * stopped.
 *
 * @param startedAtMs when the batch started
 * @param plan the plan the batch was started with
 * @param endedAtMs when it ended, or null if it has no end record
 * @param result the counts it ended with (empty while unfinished)
 */
public record Batch(
    long startedAtMs, Map<String, Object> plan, Long endedAtMs, Map<String, Object> result) {

  /**
   * The batches in a list of records, in order. An end with no start before it (the log began
   * mid-batch) is ignored.
   *
   * @param records the records, in order
   * @return the batches
   */
  public static List<Batch> of(List<Map<String, Object>> records) {
    List<Batch> batches = new ArrayList<>();
    for (Map<String, Object> record : records) {
      if (Fields.oneOf(record.get("kind"), "bulk_started", "bulk_finished")) {
        apply(batches, record);
      }
    }
    return batches;
  }

  private static void apply(List<Batch> batches, Map<String, Object> record) {
    long at = Fields.lngOr(record.get("at"), 0);
    if (Fields.is(record, "bulk_started")) {
      batches.add(new Batch(at, Fields.map(Fields.data(record).get("plan")), null, Map.of()));
    } else if (!batches.isEmpty()) {
      int open = batches.size() - 1;
      batches.set(open, batches.get(open).finish(at, Fields.data(record)));
    }
  }

  private Batch finish(long at, Map<String, Object> outcome) {
    return new Batch(startedAtMs, plan, at, outcome);
  }
}

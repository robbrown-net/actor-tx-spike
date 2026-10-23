package net.robbrown.actortx.replay;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.robbrown.actortx.policy.Policy;
import net.robbrown.actortx.selection.Candidate;
import net.robbrown.actortx.selection.Evaluation;
import net.robbrown.actortx.validation.Validated;

/**
 * Run a different strategy against a real day. For every transaction, the recorded provider answers
 * are judged again under the recorded policy with some keys changed, using the same pure selection
 * function the live system used. Each row compares three things: what the live policy would pick
 * first (recomputed, so the comparison is like for like), what the changed policy picks, and what
 * actually happened.
 *
 * <p>What this can and cannot say: it sees only providers that were actually asked; it assumes the
 * alternative would have completed; net is in minor units and the delta is what the policy change
 * alone is worth on quote values.
 */
public final class WhatIf {

  private WhatIf() {}

  /**
   * One transaction under both policies.
   *
   * @param tx the transaction
   * @param outcome how it actually ended
   * @param actual the provider it actually ended with
   * @param actualNet the net it actually achieved
   * @param base the provider the live policy would pick first, or null
   * @param baseNet its net, or null
   * @param baseLatencyMs its latency, or null
   * @param alt the provider the changed policy picks first, or null
   * @param altNet its net, or null
   * @param altLatencyMs its latency, or null
   * @param changed whether the two picks differ
   */
  public record Row(
      String tx,
      Outcome outcome,
      String actual,
      Long actualNet,
      String base,
      Long baseNet,
      Long baseLatencyMs,
      String alt,
      Long altNet,
      Long altLatencyMs,
      boolean changed) {}

  /**
   * The what-if result.
   *
   * @param overrides the keys changed
   * @param transactions how many transactions had a recorded answer to re-judge
   * @param rows one row per such transaction
   * @param comparable how many have a price both ways
   * @param changed how many of those would route differently
   * @param lostAllOptions how many would have had no option at all
   * @param netDelta what the change is worth, minor units, over the comparable transactions
   * @param latencyDeltaMs the change in total latency, over the comparable transactions
   */
  public record Result(
      Map<String, Object> overrides,
      int transactions,
      List<Row> rows,
      int comparable,
      int changed,
      int lostAllOptions,
      long netDelta,
      long latencyDeltaMs) {}

  /** The provider a judgement puts first, as plain values (nulls when nobody is left). */
  private record Top(String id, Long net, Long latencyMs) {

    static Top of(Evaluation judged) {
      if (judged.ranked().isEmpty()) {
        return new Top(null, null, null);
      }
      Candidate best = judged.ranked().get(0);
      return new Top(best.providerId(), best.net(), best.latencyMs());
    }
  }

  /**
   * Re-judge a day under changed policy keys. An override that does not make a valid policy is
   * refused up front, not silently skipped.
   *
   * @param day the day
   * @param overrides keys of the policy to change
   * @return the result, or every reason the override is not a valid policy
   */
  public static Validated<Result> run(Day day, Map<String, Object> overrides) {
    Map<String, Object> check = new LinkedHashMap<>();
    check.put("weights", Map.of("cost", 1));
    check.putAll(overrides);
    if (Policy.fromMap(check) instanceof Validated.Invalid<Policy> invalid) {
      return new Validated.Invalid<>(invalid.errors());
    }
    return new Validated.Valid<>(replay(day, overrides));
  }

  private static Result replay(Day day, Map<String, Object> overrides) {
    List<Row> rows =
        day.txOrder().stream()
            .map(id -> row(id, day.byTx().get(id), overrides))
            .flatMap(Optional::stream)
            .toList();
    List<Row> comparable =
        rows.stream().filter(r -> r.baseNet() != null && r.altNet() != null).toList();
    return new Result(
        overrides,
        rows.size(),
        rows,
        comparable.size(),
        (int) comparable.stream().filter(Row::changed).count(),
        (int) rows.stream().filter(r -> r.base() != null && r.alt() == null).count(),
        comparable.stream().mapToLong(r -> r.altNet() - r.baseNet()).sum(),
        comparable.stream().mapToLong(r -> r.altLatencyMs() - r.baseLatencyMs()).sum());
  }

  private static Optional<Row> row(
      String id, List<Map<String, Object>> rs, Map<String, Object> overrides) {
    if (Tx.all(rs, "quote_observed").isEmpty()) {
      return Optional.empty();
    }
    Top base = Top.of(Tx.valid(Tx.reselect(rs, Map.of())));
    Top alt = Top.of(Tx.valid(Tx.reselect(rs, overrides)));
    TxSummary summary = Tx.summarize(rs);
    return Optional.of(
        new Row(
            id,
            summary.outcome(),
            summary.provider(),
            summary.net(),
            base.id(),
            base.net(),
            base.latencyMs(),
            alt.id(),
            alt.net(),
            alt.latencyMs(),
            !Objects.equals(base.id(), alt.id())));
  }
}

package net.robbrown.actortx.replay;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.robbrown.actortx.money.Money;

/** Plain-text renderings of replay results, for the terminal and for evidence. */
public final class Report {

  private Report() {}

  /**
   * The verdict of a check: the headline, then every violation.
   *
   * @param verdict the verdict
   * @return the text
   */
  public static String verify(Rules.Verdict verdict) {
    String head =
        verdict.records()
            + " records, "
            + verdict.transactions()
            + " transactions ("
            + outcomes(verdict.outcomes())
            + "): "
            + (verdict.ok() ? "ALL RULES HELD" : verdict.violations().size() + " VIOLATION(S)");
    return Stream.concat(Stream.of(head), verdict.violations().stream().map(v -> violation(v)))
        .collect(Collectors.joining("\n"));
  }

  /**
   * The state at a moment, for people.
   *
   * @param state the state
   * @return the text
   */
  public static String state(State state) {
    List<String> lines =
        List.of(
            "as of " + asOf(state.asOf()),
            "transactions: "
                + outcomes(state.outcomes())
                + "; in flight: "
                + Fields.inspect(state.inFlight()),
            economicsLine(state.economics()),
            "policy in force: " + Fields.inspect(state.policy()),
            "operator actions so far: "
                + state.controls().size()
                + "; bulk batches: "
                + state.batches().size());
    return Stream.concat(
            lines.stream(),
            Stream.concat(
                Stream.of("providers:"),
                state.providers().entrySet().stream()
                    .map(e -> providerLine(e.getKey(), e.getValue()))))
        .collect(Collectors.joining("\n"));
  }

  private static String economicsLine(State.Economics economics) {
    return "economics so far: saved "
        + Money.format(economics.savedVsMedian())
        + ", margin "
        + Money.format(economics.margin())
        + ", cost "
        + economics.costPicos()
        + " pico-units over "
        + economics.transactions()
        + " transactions";
  }

  /**
   * What a what-if would have changed.
   *
   * @param what the result
   * @return the text
   */
  public static String whatIf(WhatIf.Result what) {
    String head =
        "what if "
            + Fields.inspect(what.overrides())
            + ": "
            + what.changed()
            + " of "
            + what.comparable()
            + " transactions would route differently; net "
            + signed(what.netDelta())
            + ", latency "
            + signedMs(what.latencyDeltaMs())
            + " in total"
            + lostNote(what.lostAllOptions());
    return Stream.concat(
            Stream.of(head),
            what.rows().stream().filter(WhatIf.Row::changed).map(row -> changedRow(row)))
        .collect(Collectors.joining("\n"));
  }

  /**
   * How two days differ.
   *
   * @param diff the comparison
   * @return the text
   */
  public static String diff(Compare.Diff diff) {
    String head =
        diff.common()
            + " transactions in both, "
            + diff.identical()
            + " identical, "
            + diff.divergences().size()
            + " divergence(s); only in A: "
            + diff.onlyA().size()
            + ", only in B: "
            + diff.onlyB().size();
    return Stream.concat(Stream.of(head), diff.divergences().stream().map(d -> divergenceLine(d)))
        .collect(Collectors.joining("\n"));
  }

  /**
   * The bulk batches, one line each.
   *
   * @param batches the batches
   * @return the text
   */
  public static String batches(List<Batch> batches) {
    if (batches.isEmpty()) {
      return "no bulk batches recorded";
    }
    return batches.stream().map(Report::batchLine).collect(Collectors.joining("\n"));
  }

  private static String asOf(State.AsOf asOf) {
    return asOf.position().field().key()
        + "="
        + asOf.position().value()
        + " (last record n="
        + asOf.lastN()
        + ", at "
        + asOf.lastAtMs()
        + ")";
  }

  private static String providerLine(String id, State.ProviderView view) {
    String state = Objects.toString(view.health().get("state"), "no health record");
    String breaker = Objects.toString(view.health().get("breaker"), "?");
    Map<String, Object> quote =
        Fields.pick(view.lastQuote(), List.of("outcome", "rate", "fee", "latency_ms"));
    return "  "
        + id
        + ": "
        + state
        + ", breaker "
        + breaker
        + ", last quote "
        + Fields.inspect(quote);
  }

  private static String changedRow(WhatIf.Row row) {
    long netDelta = Fields.lngOr(row.altNet(), 0) - Fields.lngOr(row.baseNet(), 0);
    return "  "
        + row.tx()
        + ": "
        + row.base()
        + " -> "
        + row.alt()
        + " (net "
        + signed(netDelta)
        + ")";
  }

  private static String divergenceLine(Compare.Divergence divergence) {
    return "  "
        + divergence.tx()
        + " "
        + divergence.field().name().toLowerCase(Locale.ROOT)
        + ": A="
        + Fields.inspect(divergence.a())
        + " B="
        + Fields.inspect(divergence.b());
  }

  private static String batchLine(Batch batch) {
    Map<String, Object> plan = batch.plan();
    return "at "
        + batch.startedAtMs()
        + ": "
        + plan.get("count")
        + " x "
        + Money.format(Fields.lngOr(plan.get("min_minor"), 0))
        + " to "
        + Money.format(Fields.lngOr(plan.get("max_minor"), 0))
        + ", "
        + plan.get("interval_ms")
        + " ms apart, "
        + plan.get("mode")
        + ", seed "
        + Fields.inspect(plan.get("seed"))
        + " -> "
        + batchResult(batch);
  }

  private static String batchResult(Batch batch) {
    if (batch.endedAtMs() == null) {
      return "no end recorded (still running, or the node stopped)";
    }
    Map<String, Object> r = batch.result();
    return r.get("status")
        + ": sent "
        + r.get("submitted")
        + ", completed "
        + r.get("completed")
        + ", failed "
        + r.get("failed")
        + ", needs review "
        + r.get("needs_review");
  }

  private static String violation(Rules.Violation v) {
    return "  ["
        + v.rule().wire()
        + "] "
        + Objects.toString(v.tx(), "-")
        + " record "
        + Objects.toString(v.n(), "?")
        + ": "
        + v.detail();
  }

  private static String outcomes(Map<Outcome, Long> outcomes) {
    return outcomes.entrySet().stream()
        .sorted(Comparator.comparing((Map.Entry<Outcome, Long> e) -> e.getKey().wire()))
        .map(e -> e.getValue() + " " + e.getKey().wire())
        .collect(Collectors.joining(", "));
  }

  private static String lostNote(int lost) {
    return lost == 0 ? "" : "; " + lost + " would have had no option at all";
  }

  private static String signed(long amount) {
    return (amount > 0 ? "+" : "") + Money.format(amount);
  }

  private static String signedMs(long ms) {
    return (ms > 0 ? "+" : "") + ms + "ms";
  }
}

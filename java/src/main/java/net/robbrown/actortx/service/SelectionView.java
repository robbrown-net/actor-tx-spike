package net.robbrown.actortx.service;

import java.util.List;
import net.robbrown.actortx.money.Money;
import net.robbrown.actortx.selection.Candidate;
import net.robbrown.actortx.selection.Selection;
import net.robbrown.actortx.selection.Selection.Outcome;

/**
 * The result of quote-and-select, for the API.
 *
 * @param outcome {@code "selected"}, {@code "fallback"} or {@code "none"}
 * @param provider the chosen provider, when selected
 * @param baseline the fallback route, when fallback
 * @param ranked the admissible candidates, best first
 * @param excluded every provider that is not in the ranking, with the reason
 * @param elapsedMs how long selection took
 * @param policy which policy layers applied ({@code "global + merchant:acme"})
 */
public record SelectionView(
    String outcome,
    String provider,
    String baseline,
    List<RankedView> ranked,
    List<ExcludedView> excluded,
    long elapsedMs,
    String policy) {

  /** One ranked candidate. */
  public record RankedView(
      String provider,
      double score,
      long netMinor,
      String net,
      String rate,
      String fee,
      long latencyMs) {}

  /** A provider that is not in the ranking, and why. */
  public record ExcludedView(String provider, String reason) {}

  /** The API view of a selection. */
  public static SelectionView of(Selection selection, String policy) {
    return new SelectionView(
        outcomeName(selection.outcome()),
        selection.outcome() instanceof Outcome.Selected s ? s.best().providerId() : null,
        selection.outcome() instanceof Outcome.Fallback f ? f.baseline() : null,
        selection.ranked().stream().map(SelectionView::ranked).toList(),
        selection.excluded().stream()
            .map(e -> new ExcludedView(e.providerId(), e.reason()))
            .toList(),
        selection.elapsedMs(),
        policy);
  }

  private static RankedView ranked(Candidate c) {
    return new RankedView(
        c.providerId(),
        c.score(),
        c.net(),
        Money.format(c.net()),
        Money.formatRate(c.quote().rate()),
        Money.format(c.quote().fee()),
        c.latencyMs());
  }

  private static String outcomeName(Outcome outcome) {
    return switch (outcome) {
      case Outcome.Selected selected -> "selected";
      case Outcome.Fallback fallback -> "fallback";
      case Outcome.None none -> "none";
    };
  }
}

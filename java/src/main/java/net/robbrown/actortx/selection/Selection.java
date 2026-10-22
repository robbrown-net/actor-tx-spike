package net.robbrown.actortx.selection;

import java.util.List;

/**
 * The result of quote-and-select.
 *
 * @param outcome what was decided
 * @param ranked the admissible candidates, best first
 * @param excluded every provider that is not in the ranking, with the reason
 * @param elapsedMs how long selection took
 */
public record Selection(
    Outcome outcome, List<Candidate> ranked, List<Excluded> excluded, long elapsedMs) {

  /** What was decided. */
  public sealed interface Outcome {

    /** The best candidate. */
    record Selected(Candidate best) implements Outcome {}

    /** No usable quote: the caller's baseline route. */
    record Fallback(String baseline) implements Outcome {}

    /** No usable quote and no baseline. */
    record None() implements Outcome {}
  }

  /** The decision for a ranking and an optional baseline. */
  public static Outcome decide(List<Candidate> ranked, String baseline) {
    if (!ranked.isEmpty()) {
      return new Outcome.Selected(ranked.get(0));
    }
    return baseline == null ? new Outcome.None() : new Outcome.Fallback(baseline);
  }
}

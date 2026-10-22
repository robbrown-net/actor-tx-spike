package net.robbrown.actortx.selection;

import java.util.List;

/**
 * The pure judgement of what each provider answered, under a policy. No processes, no clock, no
 * events, so a recorded day can be judged again with the same code that made the live decision.
 *
 * @param items the per-provider judgements, in the order observed
 * @param ranked the admissible candidates, best first
 * @param excluded every provider that is not in the ranking, with the reason
 */
public record Evaluation(List<Item> items, List<Candidate> ranked, List<Excluded> excluded) {

  /** The judgement of one provider's answer. */
  public sealed interface Item {

    /** A usable quote that passed the hard constraints. */
    record Accepted(Candidate candidate) implements Item {}

    /** A quote that failed a hard constraint. */
    record Refused(Candidate candidate, String reason) implements Item {}

    /** No quote at all: it timed out, was rejected or errored. */
    record Failed(Observation observation, String reason) implements Item {}
  }
}

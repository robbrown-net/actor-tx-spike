package net.robbrown.actortx.policy.criteria;

import java.util.Map;
import net.robbrown.actortx.selection.Candidate;

/**
 * What a criterion may use to score one candidate: the candidate itself (its net value, latency,
 * quote validity and provider health state) and where those values sit across the field. Other data
 * a criterion needs goes in {@code attributes}, keyed by the criterion's own choice of names.
 *
 * @param candidate the candidate being scored
 * @param net the range of net values across the field
 * @param latency the range of latencies across the field
 * @param attributes extra data for criteria that need it; empty unless a caller supplies some
 */
public record CriterionInput(
    Candidate candidate, Span net, Span latency, Map<String, Object> attributes) {

  /** Copies the attributes so the input cannot change under a scorer. */
  public CriterionInput {
    attributes = Map.copyOf(attributes);
  }
}

package net.robbrown.actortx.policy.criteria;

/**
 * One way of judging a candidate route. Add a criterion by writing one class that implements this
 * and listing it in {@link Criteria#standard()}; the policy validator and the scorer pick it up
 * from the registry, and a policy names it in {@code weights}.
 *
 * <p>Implementations must be pure and stateless: the same input gives the same score.
 */
public interface Criterion {

  /** The key a policy uses for this criterion in {@code weights}, e.g. {@code "cost"}. */
  String name();

  /**
   * How well the candidate does on this criterion.
   *
   * @param input the candidate and the field it is judged against
   * @return a score from 0 (worst) to 1 (best)
   */
  double score(CriterionInput input);
}

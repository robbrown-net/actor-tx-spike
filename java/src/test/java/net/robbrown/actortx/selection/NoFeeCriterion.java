package net.robbrown.actortx.selection;

import net.robbrown.actortx.policy.criteria.Criterion;
import net.robbrown.actortx.policy.criteria.CriterionInput;

/** Test criterion, added by one class: a quote with no fee scores 1, one with a fee scores 0. */
final class NoFeeCriterion implements Criterion {

  @Override
  public String name() {
    return "no_fee";
  }

  @Override
  public double score(CriterionInput input) {
    return input.candidate().quote().fee() == 0 ? 1.0 : 0.0;
  }
}

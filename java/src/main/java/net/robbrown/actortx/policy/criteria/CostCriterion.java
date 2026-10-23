package net.robbrown.actortx.policy.criteria;

/** The net value to the customer: the highest net scores 1, the lowest 0. */
public final class CostCriterion implements Criterion {

  @Override
  public String name() {
    return "cost";
  }

  @Override
  public double score(CriterionInput input) {
    return input.net().higherIsBetter(input.candidate().net());
  }
}

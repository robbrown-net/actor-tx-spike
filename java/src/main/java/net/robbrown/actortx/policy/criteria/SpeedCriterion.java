package net.robbrown.actortx.policy.criteria;

/** How fast the provider answered: the fastest scores 1, the slowest 0. */
public final class SpeedCriterion implements Criterion {

  @Override
  public String name() {
    return "speed";
  }

  @Override
  public double score(CriterionInput input) {
    return input.latency().lowerIsBetter(input.candidate().latencyMs());
  }
}

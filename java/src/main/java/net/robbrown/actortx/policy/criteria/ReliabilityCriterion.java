package net.robbrown.actortx.policy.criteria;

import java.util.EnumMap;
import java.util.Map;
import net.robbrown.actortx.provider.HealthState;

/** The provider's health when asked: a fixed factor per state; states not listed score 0. */
public final class ReliabilityCriterion implements Criterion {

  private static final Map<HealthState, Double> FACTORS =
      new EnumMap<>(
          Map.of(
              HealthState.HEALTHY, 1.0,
              HealthState.UNKNOWN, 0.7,
              HealthState.DEGRADED, 0.4));

  @Override
  public String name() {
    return "reliability";
  }

  @Override
  public double score(CriterionInput input) {
    return FACTORS.getOrDefault(input.candidate().state(), 0.0);
  }
}

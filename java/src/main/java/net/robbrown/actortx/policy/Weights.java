package net.robbrown.actortx.policy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Relative importance of each routing criterion, by the criterion's name. When built by {@link
 * Policy#fromMap} they are normalised to sum to 1. A criterion the policy does not name weighs 0.
 *
 * @param values the weight of each named criterion, in the order they were given
 */
public record Weights(Map<String, Double> values) {

  /** The three standard criteria: cost, speed and reliability. */
  public Weights(double cost, double speed, double reliability) {
    this(standard(cost, speed, reliability));
  }

  /** Copies the values, so the weights cannot change after the policy is built. */
  public Weights {
    values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }

  /** The weight of a criterion; 0 if the policy does not weigh it. */
  public double get(String criterion) {
    return values.getOrDefault(criterion, 0.0);
  }

  /** The weight of cost; 0 if not given. */
  public double cost() {
    return get("cost");
  }

  /** The weight of speed; 0 if not given. */
  public double speed() {
    return get("speed");
  }

  /** The weight of reliability; 0 if not given. */
  public double reliability() {
    return get("reliability");
  }

  private static Map<String, Double> standard(double cost, double speed, double reliability) {
    Map<String, Double> map = new LinkedHashMap<>();
    map.put("cost", cost);
    map.put("speed", speed);
    map.put("reliability", reliability);
    return map;
  }
}

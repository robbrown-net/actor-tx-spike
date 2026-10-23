package net.robbrown.actortx.policy.criteria;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The registry of routing criteria: immutable, unique by name, in a fixed order. The order is the
 * order criteria are scored and reported in, so it must not change between releases.
 */
public final class Criteria {

  private static final Criteria STANDARD =
      of(List.of(new CostCriterion(), new SpeedCriterion(), new ReliabilityCriterion()));

  private final Map<String, Criterion> byName;

  private Criteria(Map<String, Criterion> byName) {
    this.byName = byName;
  }

  /** The three criteria every policy has by default: cost, speed and reliability. */
  public static Criteria standard() {
    return STANDARD;
  }

  /**
   * A registry of these criteria, in this order.
   *
   * @throws IllegalArgumentException if two criteria share a name
   */
  public static Criteria of(List<? extends Criterion> criteria) {
    Map<String, Criterion> byName = new LinkedHashMap<>();
    for (Criterion criterion : criteria) {
      if (byName.putIfAbsent(criterion.name(), criterion) != null) {
        throw new IllegalArgumentException("duplicate criterion: " + criterion.name());
      }
    }
    return new Criteria(Collections.unmodifiableMap(byName));
  }

  /** Every criterion, in registry order. */
  public List<Criterion> all() {
    return List.copyOf(byName.values());
  }

  /** The names of every criterion, in registry order. */
  public List<String> names() {
    return List.copyOf(byName.keySet());
  }

  /** Is there a criterion with this name? */
  public boolean knows(String name) {
    return byName.containsKey(name);
  }
}

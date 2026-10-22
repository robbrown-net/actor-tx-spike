package net.robbrown.actortx.selection;

import java.util.Map;
import net.robbrown.actortx.policy.Policy;
import net.robbrown.actortx.validation.Validated;

/** Test helper: build a valid policy from plain data. */
final class Policies {

  private Policies() {}

  static Policy of(Map<String, Object> data) {
    return switch (Policy.fromMap(data)) {
      case Validated.Valid<Policy> valid -> valid.value();
      case Validated.Invalid<Policy> invalid ->
          throw new IllegalArgumentException(invalid.errors().toString());
    };
  }

  static Policy costOnly() {
    return of(Map.of("weights", Map.of("cost", 1)));
  }

  static Policy weights(double cost, double speed, double reliability) {
    return of(Map.of("weights", Map.of("cost", cost, "speed", speed, "reliability", reliability)));
  }
}

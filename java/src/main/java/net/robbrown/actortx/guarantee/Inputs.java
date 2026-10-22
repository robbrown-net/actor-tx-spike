package net.robbrown.actortx.guarantee;

import java.util.HashMap;
import java.util.Map;
import net.robbrown.actortx.validation.Parsed;

/**
 * The field checks shared by {@link Guarantee} and {@link Cutoff}. Each one mirrors an Elixir check
 * exactly, including what it refuses.
 */
final class Inputs {

  private Inputs() {}

  /**
   * A copy of {@code data} in which each of {@code keys} that is absent is present as {@code null}.
   * Elixir reads a missing key as {@code nil}, so a required field is then reported as invalid
   * through the same path as a bad one.
   */
  static Map<String, Object> withMissingAsNull(Map<String, Object> data, String... keys) {
    Map<String, Object> copy = new HashMap<>(data);
    for (String key : keys) {
      copy.putIfAbsent(key, null);
    }
    return copy;
  }

  /** A non-empty string. Blank is not empty: {@code " "} is a value, as it is in Elixir. */
  static Parsed<String> text(Object value, String message) {
    return value instanceof String s && !s.isEmpty() ? Parsed.ok(s) : Parsed.fail(message);
  }

  /**
   * A whole number from {@code min} to {@code max} inclusive; a decimal such as {@code 18.0} is
   * not.
   */
  static Parsed<Integer> inRange(Object value, int min, int max, String message) {
    if (!isIntegral(value)) {
      return Parsed.fail(message);
    }
    long n = ((Number) value).longValue();
    return n >= min && n <= max ? Parsed.ok((int) n) : Parsed.fail(message);
  }

  /** An integer (not a decimal) as the JSON decoder gives it. */
  static boolean isIntegral(Object value) {
    return value instanceof Integer || value instanceof Long;
  }
}

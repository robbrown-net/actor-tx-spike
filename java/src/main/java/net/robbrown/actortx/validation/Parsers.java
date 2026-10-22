package net.robbrown.actortx.validation;

import java.util.Map;
import java.util.TreeMap;

/** The small parsers fields are made of. */
public final class Parsers {

  private Parsers() {}

  /** A whole number of at least {@code min}. */
  public static Parsed<Long> wholeNumberAtLeast(Object value, long min, String message) {
    return value instanceof Number n && isWhole(n) && n.longValue() >= min
        ? Parsed.ok(n.longValue())
        : Parsed.fail(message);
  }

  /** A number of at least {@code min}. */
  public static Parsed<Double> numberAtLeast(Object value, double min, String message) {
    return value instanceof Number n && n.doubleValue() >= min
        ? Parsed.ok(n.doubleValue())
        : Parsed.fail(message);
  }

  /** A boolean. */
  public static Parsed<Boolean> bool(Object value, String message) {
    return value instanceof Boolean b ? Parsed.ok(b) : Parsed.fail(message);
  }

  /** A string with something in it. */
  public static boolean present(Object value) {
    return value instanceof String s && !s.isBlank();
  }

  /** The map with its keys as strings; anything that is not a map is an empty map. */
  public static Map<String, Object> stringKeys(Object value) {
    Map<String, Object> result = new TreeMap<>();
    if (value instanceof Map<?, ?> map) {
      map.forEach((k, v) -> result.put(String.valueOf(k), v));
    }
    return result;
  }

  private static boolean isWhole(Number n) {
    return n.doubleValue() == Math.rint(n.doubleValue());
  }
}

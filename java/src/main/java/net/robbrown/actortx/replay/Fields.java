package net.robbrown.actortx.replay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import net.robbrown.actortx.payment.Outcome;

/**
 * Tolerant reads of decoded audit records. A record is a plain map as the JSON decodes it; a
 * missing field, or a value of the wrong shape, reads as absent instead of failing, so a log
 * written by any system that follows the format can be judged. Also the Elixir-style rendering of
 * values ({@link #inspect}) that the evidence text uses.
 */
final class Fields {

  private Fields() {}

  /** The value as a map with string keys; anything that is not a map is an empty map. */
  static Map<String, Object> map(Object value) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (value instanceof Map<?, ?> map) {
      map.forEach((key, item) -> out.put(String.valueOf(key), item));
    }
    return out;
  }

  /** The value as a list; anything that is not a list is an empty list. */
  static List<Object> list(Object value) {
    List<Object> out = new ArrayList<>();
    if (value instanceof List<?> items) {
      out.addAll(items);
    }
    return out;
  }

  /** The value as a list of strings (a mutable list, so {@code contains} accepts nulls). */
  static List<String> strings(Object value) {
    List<String> out = new ArrayList<>();
    for (Object item : list(value)) {
      out.add(String.valueOf(item));
    }
    return out;
  }

  /** The value if it is text, else null. */
  static String str(Object value) {
    return value instanceof String text ? text : null;
  }

  /** The value if it is a number (as a whole), else null. */
  static Long lng(Object value) {
    return value instanceof Number number ? Long.valueOf(number.longValue()) : null;
  }

  /** The value as a whole number, or the fallback if it is not one. */
  static long lngOr(Object value, long fallback) {
    Long found = lng(value);
    return found == null ? fallback : found;
  }

  /** The value if it is a number (as a decimal), else null. */
  static Double dbl(Object value) {
    return value instanceof Number number ? Double.valueOf(number.doubleValue()) : null;
  }

  /** The value as a decimal, or the fallback if it is not a number. */
  static double dblOr(Object value, double fallback) {
    Double found = dbl(value);
    return found == null ? fallback : found;
  }

  /** Is the value the JSON {@code true}? */
  static boolean flag(Object value) {
    return Boolean.TRUE.equals(value);
  }

  /** Is the value one of the options? Null-safe. */
  static boolean oneOf(Object value, String... options) {
    return Arrays.asList(options).contains(value);
  }

  /** Is this record of the given kind? */
  static boolean is(Map<String, Object> record, String kind) {
    return kind.equals(record.get("kind"));
  }

  /** The record's {@code data} as a map (empty if absent). */
  static Map<String, Object> data(Map<String, Object> record) {
    return map(record.get("data"));
  }

  /** The value at a path of keys, or null if any step is absent. */
  static Object dig(Object value, String... path) {
    Object current = value;
    for (String key : path) {
      current = map(current).get(key);
    }
    return current;
  }

  /** The last record of a list, or an empty map if there are none. */
  static Map<String, Object> last(List<Map<String, Object>> records) {
    return records.isEmpty() ? Map.of() : records.get(records.size() - 1);
  }

  /** The entries of a map whose keys are present, in the order given. */
  static Map<String, Object> pick(Map<String, Object> source, List<String> keys) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (String key : keys) {
      if (source.containsKey(key)) {
        out.put(key, source.get(key));
      }
    }
    return out;
  }

  /**
   * How Elixir would print a value: strings quoted, lists and maps in brackets, {@code nil} for
   * null. Used for the evidence text, so a person reads the same shapes the log was written in.
   */
  static String inspect(Object value) {
    if (value instanceof Map<?, ?> map) {
      return inspectMap(map(map));
    }
    if (value instanceof List<?> items) {
      return "[" + items.stream().map(Fields::inspect).collect(Collectors.joining(", ")) + "]";
    }
    return scalar(value);
  }

  private static String inspectMap(Map<String, Object> map) {
    String body =
        map.keySet().stream()
            .sorted()
            .map(key -> inspect(key) + " => " + inspect(map.get(key)))
            .collect(Collectors.joining(", "));
    return "%{" + body + "}";
  }

  private static String scalar(Object value) {
    if (value == null) {
      return "nil";
    }
    if (value instanceof String text) {
      return "\"" + text + "\"";
    }
    return atom(value);
  }

  // An outcome is an atom; an absent one (a transaction still open) is the atom :open.
  private static String atom(Object value) {
    if (value instanceof Optional<?> optional) {
      return optional.map(Fields::scalar).orElse(":" + Tx.OPEN);
    }
    if (value instanceof Outcome outcome) {
      return ":" + outcome.wire();
    }
    return String.valueOf(value);
  }
}

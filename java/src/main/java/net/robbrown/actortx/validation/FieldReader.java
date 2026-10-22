package net.robbrown.actortx.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Reads fields out of plain data (a decoded JSON map), collecting every error rather than stopping
 * at the first. A field that is absent keeps its default; one that is present must parse.
 */
public final class FieldReader {

  private final Map<String, Object> raw;
  private final String prefix;
  private final List<String> errors = new ArrayList<>();

  /**
   * A reader over {@code raw}; error messages are {@code "<prefix><key>: <message>"}.
   *
   * @param raw the data
   * @param prefix put in front of each key in messages (for example {@code "regions.IN."})
   */
  public FieldReader(Map<String, Object> raw, String prefix) {
    this.raw = raw;
    this.prefix = prefix;
  }

  /** Report every key of the data that is not in {@code known}. */
  public FieldReader rejectUnknown(Set<String> known) {
    Set<String> extra = new TreeSet<>(raw.keySet());
    extra.removeAll(known);
    extra.forEach(key -> errors.add(prefix + "unknown key: " + key));
    return this;
  }

  /** The parsed value of {@code key}, or {@code defaultValue} if absent or if it does not parse. */
  public <T> T read(String key, T defaultValue, Function<Object, Parsed<T>> parse) {
    if (!raw.containsKey(key)) {
      return defaultValue;
    }
    Parsed<T> parsed = parse.apply(raw.get(key));
    if (parsed.isOk()) {
      return parsed.value();
    }
    errors.add(prefix + key + ": " + parsed.error());
    return defaultValue;
  }

  /** Every error so far, in the order found. */
  public List<String> errors() {
    return List.copyOf(errors);
  }

  /** Valid with {@code value} if nothing went wrong, else every error. */
  public <T> Validated<T> finish(T value) {
    return Validated.of(value, errors);
  }
}

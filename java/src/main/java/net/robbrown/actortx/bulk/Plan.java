package net.robbrown.actortx.bulk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import net.robbrown.actortx.money.Money;
import net.robbrown.actortx.validation.FieldReader;
import net.robbrown.actortx.validation.Parsed;
import net.robbrown.actortx.validation.Parsers;
import net.robbrown.actortx.validation.Validated;

/**
 * A validated plan for sending many payments: how many, the amount range, the pause between
 * submissions, the admission cap the sender keeps to itself, and which settings are fixed or
 * randomised.
 *
 * <p>Every payment gets an amount drawn uniformly between {@code minMinor} and {@code maxMinor}
 * (inclusive, in minor units). In {@link Mode#FIXED} every other setting comes from {@code fixed}.
 * In {@link Mode#RANDOM} each setting listed in {@code randomise} is drawn for every payment and
 * the rest come from {@code fixed}. A plan with a {@code seed} produces the same payments every
 * time.
 *
 * @param count how many payments, 1 to 10,000
 * @param minMinor the smallest amount, in minor units
 * @param maxMinor the largest amount, in minor units, not below {@code minMinor}
 * @param intervalMs the pause between submissions, 0 to 60,000
 * @param maxInFlight the sender's own limit on payments still running; 0 for none
 * @param mode whether settings are fixed or randomised
 * @param randomise the settings drawn at random in {@link Mode#RANDOM}
 * @param fixed the settings used as given (a missing one is left out of the payment)
 * @param seed the random seed, or {@code null} for an unseeded plan
 */
public record Plan(
    int count,
    long minMinor,
    long maxMinor,
    long intervalMs,
    int maxInFlight,
    Mode mode,
    List<String> randomise,
    Map<String, String> fixed,
    Long seed) {

  /** Whether the settings are fixed or drawn at random for every payment. */
  public enum Mode {
    /** Every setting comes from the fixed map. */
    FIXED,
    /** The settings listed in {@code randomise} are drawn at random. */
    RANDOM
  }

  private static final long MAX_COUNT = 10_000;
  private static final long MAX_INTERVAL_MS = 60_000;
  private static final long DEFAULT_COUNT = 10;
  private static final long DEFAULT_MIN_MINOR = 1_000;
  private static final long DEFAULT_MAX_MINOR = 100_000;
  private static final long DEFAULT_INTERVAL_MS = 100;
  private static final String POSITIVE = "must be a positive amount";

  /** Canonical constructor: the lists and maps are copied so a plan cannot change under a batch. */
  public Plan {
    randomise = List.copyOf(randomise);
    fixed = Map.copyOf(fixed);
  }

  /**
   * Validate parameters (as decoded from JSON, with snake_case keys) into a plan. Every problem is
   * reported at once. Absent parameters take their defaults: 10 payments of 10.00 to 1,000.00, 100
   * ms apart, fixed, no in-flight cap, unseeded.
   *
   * @param params the parameters
   * @return the plan, or every error as {@code "field: message"}
   */
  public static Validated<Plan> of(Map<String, Object> params) {
    FieldReader reader = new FieldReader(params, "");
    long count = reader.read("count", DEFAULT_COUNT, raw -> parseCount(raw));
    long min = reader.read("min_amount", DEFAULT_MIN_MINOR, raw -> parseAmount(raw));
    long max = reader.read("max_amount", DEFAULT_MAX_MINOR, raw -> parseAmount(raw));
    long interval = reader.read("interval_ms", DEFAULT_INTERVAL_MS, raw -> parseInterval(raw));
    long inFlight = reader.read("max_in_flight", 0L, raw -> parseInFlight(raw));
    Mode mode = reader.read("mode", Mode.FIXED, raw -> parseMode(raw));
    List<String> randomise =
        reader.read("randomise", CommandGenerator.SETTINGS, raw -> parseRandomise(raw));
    Map<String, String> fixed =
        reader.read("fixed", Map.<String, String>of(), raw -> parseFixed(raw));
    Long seed = reader.read("seed", null, raw -> parseSeed(raw));
    Plan plan =
        new Plan(
            Math.toIntExact(count),
            min,
            max,
            interval,
            Math.toIntExact(inFlight),
            mode,
            randomise,
            fixed,
            seed);
    return Validated.of(plan, errorsOf(reader, min, max));
  }

  /** The plan as plain data, amounts in minor units, for the audit log. */
  public Map<String, Object> toMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("count", count);
    map.put("min_minor", minMinor);
    map.put("max_minor", maxMinor);
    map.put("interval_ms", intervalMs);
    map.put("max_in_flight", maxInFlight);
    map.put("mode", mode.name().toLowerCase(Locale.ROOT));
    map.put("randomise", randomise);
    map.put("fixed", fixed);
    map.put("seed", seed);
    return map;
  }

  /**
   * A one-line description for people, e.g. {@code "10 × 10.00 to 1000.00, 100 ms apart, fixed"}.
   */
  public String describe() {
    return count
        + " × "
        + Money.format(minMinor)
        + " to "
        + Money.format(maxMinor)
        + ", "
        + intervalMs
        + " ms apart, "
        + mode.name().toLowerCase(Locale.ROOT)
        + capped()
        + seeded();
  }

  /** The random source for this plan: seeded plans get their own, others the injected one. */
  RandomGenerator generatorFor(RandomGenerator unseeded) {
    return seed == null ? unseeded : new SplittableRandom(seed);
  }

  private String capped() {
    return maxInFlight == 0 ? "" : ", at most " + maxInFlight + " in flight";
  }

  private String seeded() {
    return seed == null ? "" : ", seed " + seed;
  }

  private static List<String> errorsOf(FieldReader reader, long min, long max) {
    List<String> errors = new ArrayList<>(reader.errors());
    if (errors.isEmpty() && max < min) {
      errors.add("max_amount: must not be below min_amount");
    }
    return errors;
  }

  private static Parsed<Long> parseCount(Object value) {
    return whole(value, 1, MAX_COUNT, "must be a whole number from 1 to " + MAX_COUNT);
  }

  private static Parsed<Long> parseInterval(Object value) {
    return whole(
        value,
        0,
        MAX_INTERVAL_MS,
        "must be a whole number of milliseconds from 0 to " + MAX_INTERVAL_MS);
  }

  private static Parsed<Long> parseInFlight(Object value) {
    return whole(value, 0, MAX_COUNT, "must be a whole number from 0 (no limit) to " + MAX_COUNT);
  }

  private static Parsed<Long> whole(Object value, long min, long max, String message) {
    Parsed<Long> parsed = Parsers.wholeNumberAtLeast(value, min, message);
    return parsed.isOk() && parsed.value() <= max ? parsed : Parsed.fail(message);
  }

  /** A decimal amount in major units (a number or a string), as minor units; positive only. */
  private static Parsed<Long> parseAmount(Object value) {
    Long minor = minorOrNull(value);
    return minor != null && minor > 0 ? Parsed.ok(minor) : Parsed.fail(POSITIVE);
  }

  /** The minor units of a decimal amount, or {@code null} when it is not one. */
  private static Long minorOrNull(Object value) {
    if (!(value instanceof Number || value instanceof String)) {
      return null;
    }
    try {
      return Money.minor(String.valueOf(value));
    } catch (ArithmeticException | NumberFormatException e) {
      return null;
    }
  }

  private static Parsed<Mode> parseMode(Object value) {
    if ("fixed".equals(value)) {
      return Parsed.ok(Mode.FIXED);
    }
    if ("random".equals(value)) {
      return Parsed.ok(Mode.RANDOM);
    }
    return Parsed.fail("must be \"fixed\" or \"random\"");
  }

  private static Parsed<List<String>> parseRandomise(Object value) {
    if (!(value instanceof List<?> items)) {
      return Parsed.fail("must be a list of setting names");
    }
    List<String> names = items.stream().map(item -> String.valueOf(item)).toList();
    return unknownSettings(names)
        .map(unknown -> Parsed.<List<String>>fail(unknown))
        .orElseGet(() -> Parsed.ok(names));
  }

  private static Parsed<Map<String, String>> parseFixed(Object value) {
    if (!(value instanceof Map<?, ?>)) {
      return Parsed.fail("must be a map of setting to value");
    }
    Map<String, Object> raw = Parsers.stringKeys(value);
    return unknownSettings(raw.keySet())
        .map(unknown -> Parsed.<Map<String, String>>fail(unknown))
        .orElseGet(() -> Parsed.ok(given(raw)));
  }

  private static Parsed<Long> parseSeed(Object value) {
    return value == null
        ? Parsed.ok(null)
        : Parsers.wholeNumberAtLeast(value, Long.MIN_VALUE, "must be a whole number or empty");
  }

  /** The settings that are not known, as an error message, or empty when all are known. */
  private static Optional<String> unknownSettings(Iterable<String> names) {
    List<String> unknown = new ArrayList<>();
    names.forEach(name -> keepIfUnknown(name, unknown));
    return unknown.isEmpty()
        ? Optional.empty()
        : Optional.of("unknown setting(s): " + String.join(", ", unknown));
  }

  private static void keepIfUnknown(String name, List<String> unknown) {
    if (!CommandGenerator.SETTINGS.contains(name)) {
      unknown.add(name);
    }
  }

  /** The fixed settings that were given: a missing, blank or null value is left out. */
  private static Map<String, String> given(Map<String, Object> raw) {
    Map<String, String> result = new LinkedHashMap<>();
    raw.forEach((key, value) -> putIfGiven(result, key, value));
    return result;
  }

  private static void putIfGiven(Map<String, String> result, String key, Object value) {
    if (value != null && !String.valueOf(value).isEmpty()) {
      result.put(key, String.valueOf(value));
    }
  }
}

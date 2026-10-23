package net.robbrown.actortx.policy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.robbrown.actortx.policy.criteria.Criteria;
import net.robbrown.actortx.provider.HealthState;
import net.robbrown.actortx.selection.Candidate;
import net.robbrown.actortx.selection.Selector;
import net.robbrown.actortx.validation.FieldReader;
import net.robbrown.actortx.validation.Parsed;
import net.robbrown.actortx.validation.Parsers;
import net.robbrown.actortx.validation.Validated;

/**
 * Routing and scoring policy as validated data.
 *
 * <ul>
 *   <li>{@code weights}: relative importance of each criterion in {@link Criteria} by name (by
 *       default cost, speed and reliability).
 *   <li>{@code minValidityMs} and {@code maxLatencyMs}: hard constraints, see {@link #check}.
 *   <li>{@code maxBpsFromBest}: never accept a quote more than this many basis points worse than
 *       the best net received; {@code null} is no limit. Applied before scoring, so speed and
 *       reliability can never buy an unacceptable price.
 *   <li>{@code excludeStates}: provider health states that are never called.
 * </ul>
 *
 * Scoring is pure and deterministic: see {@link Selector#scores}, a weighted sum of each
 * criterion's 0..1 score.
 *
 * @param weights relative importance of each criterion, by name
 * @param minValidityMs least remaining quote life accepted, in milliseconds
 * @param maxLatencyMs slowest provider answer accepted, or {@code null} for no limit
 * @param maxBpsFromBest widest gap to the best net accepted, or {@code null} for no limit
 * @param excludeStates health states that are never called
 */
public record Policy(
    Weights weights,
    long minValidityMs,
    Long maxLatencyMs,
    Double maxBpsFromBest,
    Set<HealthState> excludeStates) {

  /**
   * The policy as plain data, exactly as it would be given to {@link #fromMap} (for the audit log).
   */
  public Map<String, Object> toMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("weights", new LinkedHashMap<>(weights.values()));
    map.put("min_validity_ms", minValidityMs);
    map.put("max_latency_ms", maxLatencyMs);
    map.put("max_bps_from_best", maxBpsFromBest);
    map.put("exclude_states", excludeStates.stream().map(HealthState::wire).sorted().toList());
    return map;
  }

  /** Why {@link #check} excluded a candidate. */
  public enum Exclusion {
    /** The quote has less validity left than {@code minValidityMs}. */
    QUOTE_EXPIRES_TOO_SOON,
    /** The provider answered more slowly than {@code maxLatencyMs}. */
    TOO_SLOW
  }

  /**
   * The candidates within {@code maxBpsFromBest} of the best net value, and those dropped with how
   * far worse they were.
   */
  public record WithinBest(List<Candidate> kept, List<TooDear> dropped) {}

  /** A candidate dropped for being {@code bps} basis points worse than the best. */
  public record TooDear(Candidate candidate, double bps) {}

  private static final Weights DEFAULT_WEIGHTS = new Weights(0.6, 0.3, 0.1);
  private static final Set<HealthState> DEFAULT_EXCLUDED =
      Set.of(HealthState.STALLED, HealthState.QUARANTINED);
  private static final Set<String> KEYS =
      Set.of("weights", "min_validity_ms", "max_latency_ms", "max_bps_from_best", "exclude_states");
  private static final double BPS_PER_UNIT = 10_000;
  private static final String MAX_LATENCY_MESSAGE = "must be nil or a positive integer";
  private static final String UNKNOWN_STATE =
      "unknown state (expected one of [:unknown, :healthy, :degraded, :stalled, :quarantined])";

  /** The policy used when nothing is configured: weights 0.6, 0.3 and 0.1, no limits. */
  public static Policy defaults() {
    return new Policy(DEFAULT_WEIGHTS, 0, null, null, DEFAULT_EXCLUDED);
  }

  /** Validate plain data (decoded JSON) against the standard criteria. */
  public static Validated<Policy> fromMap(Map<String, Object> data) {
    return fromMap(data, Criteria.standard());
  }

  /**
   * Validate plain data (decoded JSON). Every problem is reported, not just the first. A name in
   * {@code weights} must be a criterion of the registry; missing ones weigh 0.
   */
  public static Validated<Policy> fromMap(Map<String, Object> data, Criteria criteria) {
    FieldReader reader = new FieldReader(data, "").rejectUnknown(KEYS);
    Weights weights = reader.read("weights", DEFAULT_WEIGHTS, raw -> parseWeights(raw, criteria));
    long minValidity = reader.read("min_validity_ms", 0L, raw -> parseMinValidity(raw));
    Long maxLatency = reader.read("max_latency_ms", null, raw -> parseMaxLatency(raw));
    Double maxBps = reader.read("max_bps_from_best", null, raw -> parseMaxBps(raw));
    Set<HealthState> states =
        reader.read("exclude_states", DEFAULT_EXCLUDED, raw -> parseStates(raw));
    return reader.finish(new Policy(weights, minValidity, maxLatency, maxBps, states));
  }

  /** Is a provider in this health state excluded from calling? {@code DOWN} always is. */
  public boolean excludesState(HealthState state) {
    return state == HealthState.DOWN || excludeStates.contains(state);
  }

  /** The hard constraints on a candidate's validity and latency; empty if it may be considered. */
  public Optional<Exclusion> check(Candidate candidate) {
    if (candidate.validityMs() < minValidityMs) {
      return Optional.of(Exclusion.QUOTE_EXPIRES_TOO_SOON);
    }
    return tooSlow(candidate.latencyMs()) ? Optional.of(Exclusion.TOO_SLOW) : Optional.empty();
  }

  /** Split candidates into those within {@code maxBpsFromBest} of the best net and the rest. */
  public WithinBest withinBest(List<Candidate> candidates) {
    if (maxBpsFromBest == null || candidates.isEmpty()) {
      return new WithinBest(candidates, List.of());
    }
    long best = candidates.stream().mapToLong(Candidate::net).max().orElseThrow();
    Map<Boolean, List<TooDear>> split =
        candidates.stream()
            .map(c -> new TooDear(c, bpsWorse(c.net(), best)))
            .collect(Collectors.partitioningBy(t -> t.bps() <= maxBpsFromBest));
    return new WithinBest(
        split.get(true).stream().map(TooDear::candidate).toList(), split.get(false));
  }

  /**
   * Scores (higher is better) for the candidates under the standard criteria, in the same order.
   * Empty in, empty out.
   */
  public List<Double> scores(List<Candidate> candidates) {
    return Selector.scores(candidates, this, Criteria.standard());
  }

  private boolean tooSlow(long latencyMs) {
    return maxLatencyMs != null && latencyMs > maxLatencyMs;
  }

  private static double bpsWorse(long net, long best) {
    return best > 0 ? (double) (best - net) / best * BPS_PER_UNIT : 0.0;
  }

  private static Parsed<Weights> parseWeights(Object raw, Criteria criteria) {
    if (!(raw instanceof Map<?, ?>)) {
      return Parsed.fail("must be a map");
    }
    Map<String, Object> map = Parsers.stringKeys(raw);
    List<String> extra = map.keySet().stream().filter(k -> !criteria.knows(k)).sorted().toList();
    if (!extra.isEmpty()) {
      return Parsed.fail("unknown weights: " + String.join(", ", extra));
    }
    List<String> names = criteria.names();
    return usableWeights(names, names.stream().map(k -> map.getOrDefault(k, 0)).toList());
  }

  private static Parsed<Weights> usableWeights(List<String> names, List<Object> values) {
    if (!values.stream().allMatch(Policy::isNonNegativeNumber)) {
      return Parsed.fail("must be non-negative numbers");
    }
    List<Double> nums = values.stream().map(v -> ((Number) v).doubleValue()).toList();
    double total = nums.stream().reduce(0.0, Double::sum);
    if (total == 0) {
      return Parsed.fail("must not all be zero");
    }
    Map<String, Double> normalised = new LinkedHashMap<>();
    for (int i = 0; i < names.size(); i++) {
      normalised.put(names.get(i), nums.get(i) / total);
    }
    return Parsed.ok(new Weights(normalised));
  }

  private static boolean isNonNegativeNumber(Object value) {
    return value instanceof Number n && n.doubleValue() >= 0;
  }

  private static Parsed<Long> parseMinValidity(Object raw) {
    return Parsers.wholeNumberAtLeast(raw, 0, "must be a non-negative integer");
  }

  private static Parsed<Long> parseMaxLatency(Object raw) {
    return raw == null ? Parsed.ok(null) : Parsers.wholeNumberAtLeast(raw, 1, MAX_LATENCY_MESSAGE);
  }

  private static Parsed<Double> parseMaxBps(Object raw) {
    return raw == null
        ? Parsed.ok(null)
        : Parsers.numberAtLeast(raw, 0, "must be nil or a non-negative number");
  }

  private static Parsed<Set<HealthState>> parseStates(Object raw) {
    if (!(raw instanceof List<?> names)) {
      return Parsed.fail("must be a list");
    }
    List<HealthState> states =
        names.stream().map(Policy::parseState).flatMap(Optional::stream).toList();
    return states.size() == names.size()
        ? Parsed.ok(Set.copyOf(states))
        : Parsed.fail(UNKNOWN_STATE);
  }

  /** A configurable state by its name. {@code DOWN} is not configurable: it is always excluded. */
  private static Optional<HealthState> parseState(Object name) {
    return HealthState.parse(name).filter(s -> s != HealthState.DOWN);
  }
}

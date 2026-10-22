package net.robbrown.actortx.eligibility;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import net.robbrown.actortx.validation.Validated;

/**
 * Jurisdiction as a hard constraint. Regulation decides which providers may carry a corridor, which
 * instruments and customer types are allowed there, and where the day's records may live.
 *
 * <p>A transaction touches its origin and destination regions; a region without a rule uses the
 * default. Where several apply the strictest result wins: instruments, customer types, currencies
 * and providers must be allowed by all of them, retention is the longest, and records go to the
 * union of the regions' logs unless a local-only region is involved.
 */
public final class Eligibility {

  private static final String DEFAULT_REGION = "default";

  private final Rule defaultRule;
  private final Map<String, Rule> regionRules;

  /** Built by {@link #fromMap} or {@link #open}. */
  Eligibility(Rule defaultRule, Map<String, Rule> regionRules) {
    this.defaultRule = defaultRule;
    this.regionRules = Map.copyOf(regionRules);
  }

  /** Everything permitted, everything in the global log: for when no rules are configured. */
  public static Eligibility open() {
    return new Eligibility(Rule.defaults(), Map.of());
  }

  /**
   * Build from plain data with {@code "default"} and {@code "regions"} keys. Returns every error
   * found, not just the first.
   */
  public static Validated<Eligibility> fromMap(Map<String, Object> data) {
    return RuleParser.parse(data);
  }

  /**
   * Decide a request. The request keys are {@code originRegion}, {@code destinationRegion}, {@code
   * instrument}, {@code customerType}, {@code sourceCurrency} and {@code destinationCurrency};
   * instrument and customer type default to spot and business.
   */
  public Decision decide(Map<String, Object> request) {
    List<String> regions = regionsOf(request);
    List<Applied> applied = appliedTo(regions);
    Instrument instrument = resolve(request, "instrument", Instrument::parse, Instrument.SPOT);
    CustomerType customer =
        resolve(request, "customerType", CustomerType::parse, CustomerType.BUSINESS);
    String source = Objects.toString(request.get("sourceCurrency"), null);
    String destination = Objects.toString(request.get("destinationCurrency"), null);
    List<String> currencies = Stream.of(source, destination).toList();
    List<String> reasons =
        Stream.concat(
                unrecognised(request).stream(),
                violations(applied, instrument, customer, currencies).stream())
            .toList();
    List<Rule> rules = applied.stream().map(Applied::rule).toList();
    return new Decision(
        reasons.isEmpty(),
        reasons,
        regions,
        instrument,
        customer,
        source,
        destination,
        providerRules(rules),
        logsOf(rules),
        retentionOf(rules));
  }

  /** May this provider carry the transaction decided by {@code decision}? */
  public static boolean providerAllowed(Decision decision, String providerId) {
    Objects.requireNonNull(providerId, "providerId");
    return decision.providerRules().stream().allMatch(rule -> rule.permits(providerId));
  }

  /** Why a provider is not allowed, for the record. */
  public static String denialReason(Decision decision, String providerId) {
    return providerId
        + " is not eligible for the "
        + String.join("/", decision.regions())
        + " corridor";
  }

  /** A region as it applies to one request: its name and the rule that governs it. */
  private record Applied(String region, Rule rule) {}

  private List<Applied> appliedTo(List<String> regions) {
    if (regions.isEmpty()) {
      return List.of(new Applied(DEFAULT_REGION, defaultRule));
    }
    return regions.stream()
        .map(region -> new Applied(region, regionRules.getOrDefault(region, defaultRule)))
        .toList();
  }

  private static List<String> regionsOf(Map<String, Object> request) {
    return Stream.of("originRegion", "destinationRegion")
        .map(request::get)
        .filter(Objects::nonNull)
        .map(Object::toString)
        .distinct()
        .toList();
  }

  /** The parsed value, the default when absent, or null when present but not recognised. */
  private static <T> T resolve(
      Map<String, Object> request, String key, Function<Object, Optional<T>> parse, T fallback) {
    Object raw = request.get(key);
    return raw == null ? fallback : parse.apply(raw).orElse(null);
  }

  /**
   * An instrument or customer type that was given but is not known is refused, never quietly
   * replaced by the default.
   */
  private static List<String> unrecognised(Map<String, Object> request) {
    return Stream.of(
            unknown(request, "instrument", Instrument::parse),
            unknown(request, "customerType", CustomerType::parse))
        .flatMap(Optional::stream)
        .toList();
  }

  private static <T> Optional<String> unknown(
      Map<String, Object> request, String key, Function<Object, Optional<T>> parse) {
    Object raw = request.get(key);
    return raw == null || parse.apply(raw).isPresent()
        ? Optional.empty()
        : Optional.of("unknown " + key + ": " + raw);
  }

  private static List<String> violations(
      List<Applied> applied,
      Instrument instrument,
      CustomerType customer,
      List<String> currencies) {
    return applied.stream()
        .flatMap(region -> reasonsIn(region, instrument, customer, currencies).stream())
        .toList();
  }

  private static List<String> reasonsIn(
      Applied applied, Instrument instrument, CustomerType customer, List<String> currencies) {
    Rule rule = applied.rule();
    String region = applied.region();
    Stream<String> kinds =
        Stream.of(
                missing(instrument, rule.instruments())
                    .map(i -> i.wire() + " is not permitted in " + region),
                missing(customer, rule.customerTypes())
                    .map(c -> c.wire() + " customers are not permitted in " + region))
            .flatMap(Optional::stream);
    Stream<String> codes =
        currencies.stream()
            .filter(Objects::nonNull)
            .filter(code -> !rule.currencies().allows(code))
            .map(code -> code + " cannot be used in " + region);
    return Stream.concat(kinds, codes).toList();
  }

  /** The value if it is given and the allowed set does not contain it. */
  private static <T> Optional<T> missing(T value, Set<T> allowed) {
    return value == null || allowed.contains(value) ? Optional.empty() : Optional.of(value);
  }

  private static List<ProviderRule> providerRules(List<Rule> rules) {
    return rules.stream()
        .map(Rule::providers)
        .filter(provider -> !(provider instanceof ProviderRule.All))
        .toList();
  }

  /** The records' logs: the local-only regions' when there are any, else every region's. */
  private static List<String> logsOf(List<Rule> rules) {
    List<Rule> local = rules.stream().filter(Rule::localOnly).toList();
    List<Rule> chosen = local.isEmpty() ? rules : local;
    return chosen.stream().flatMap(rule -> rule.logs().stream()).distinct().sorted().toList();
  }

  private static Integer retentionOf(List<Rule> rules) {
    return rules.stream()
        .map(Rule::retentionDays)
        .filter(Objects::nonNull)
        .max(Comparator.naturalOrder())
        .orElse(null);
  }
}

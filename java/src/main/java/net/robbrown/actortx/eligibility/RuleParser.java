package net.robbrown.actortx.eligibility;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.robbrown.actortx.validation.FieldReader;
import net.robbrown.actortx.validation.Parsed;
import net.robbrown.actortx.validation.Parsers;
import net.robbrown.actortx.validation.Validated;

/**
 * Turns plain data into a validated {@link Eligibility}, collecting every error rather than
 * stopping at the first. The messages are those of the Elixir {@code Eligibility.Parser}.
 */
final class RuleParser {

  private static final String DEFAULT = "default";
  private static final String REGIONS = "regions";
  private static final Set<String> TOP_KEYS = Set.of(DEFAULT, REGIONS);
  private static final Set<String> RULE_KEYS =
      Set.of(
          "instruments",
          "customer_types",
          "currencies",
          "providers",
          "logs",
          "local_only",
          "retention_days");
  private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
  private static final Pattern LOG_NAME = Pattern.compile("[A-Za-z0-9_-]+");
  private static final Map<String, Function<Set<String>, ProviderRule>> PROVIDER_KINDS =
      Map.of("allow", ProviderRule.Allow::new, "deny", ProviderRule.Deny::new);

  private static final String PROVIDERS_HELP =
      "must be \"all\", {\"allow\": [...]} or {\"deny\": [...]}";
  private static final String CURRENCIES_HELP =
      "must be \"all\" or a non-empty list of currency codes";
  private static final String CURRENCY_CODE_HELP = "currency codes are three capital letters";
  private static final String LOG_LIST_HELP = "must be a non-empty list of log names";
  private static final String LOG_NAME_HELP = "log names must be letters, digits, - or _";
  private static final String LIST_HELP = "must be a non-empty list";
  private static final String BOOLEAN_HELP = "must be true or false";
  private static final String RETENTION_HELP = "must be nil or a positive number of days";
  private static final String INSTRUMENT_HELP =
      "unknown value (expected some of [:spot, :forward])";
  private static final String CUSTOMER_HELP =
      "unknown value (expected some of [:business, :retail])";

  private RuleParser() {}

  /** Parse the whole eligibility; the value is only meaningful when there are no errors. */
  static Validated<Eligibility> parse(Map<String, Object> data) {
    Map<String, Object> top = Parsers.stringKeys(data);
    FieldReader reader = new FieldReader(top, "").rejectUnknown(TOP_KEYS);
    Outcome<Rule> defaults = rule(top.getOrDefault(DEFAULT, Map.of()), DEFAULT);
    Outcome<Map<String, Rule>> regions = regions(top.getOrDefault(REGIONS, Map.of()));
    List<String> errors =
        Stream.of(reader.errors(), defaults.errors(), regions.errors())
            .flatMap(List::stream)
            .toList();
    return Validated.of(new Eligibility(defaults.value(), regions.value()), errors);
  }

  /** A parsed value together with the errors found while parsing it. */
  private record Outcome<T>(T value, List<String> errors) {}

  private static Outcome<Map<String, Rule>> regions(Object raw) {
    if (!(raw instanceof Map<?, ?> map)) {
      return new Outcome<>(Map.of(), List.of(REGIONS + ": must be a map"));
    }
    Map<String, Rule> rules = new TreeMap<>();
    List<String> errors = new ArrayList<>();
    for (Map.Entry<String, Object> entry : Parsers.stringKeys(map).entrySet()) {
      Outcome<Rule> rule = rule(entry.getValue(), REGIONS + "." + entry.getKey());
      rules.put(entry.getKey(), rule.value());
      errors.addAll(rule.errors());
    }
    return new Outcome<>(rules, errors);
  }

  private static Outcome<Rule> rule(Object raw, String path) {
    if (!(raw instanceof Map<?, ?> map)) {
      return new Outcome<>(Rule.defaults(), List.of(path + ": must be a map"));
    }
    Map<String, Object> fields = Parsers.stringKeys(map);
    FieldReader reader = new FieldReader(fields, path + ".");
    Rule rule = readRule(reader);
    List<String> unknown =
        fields.keySet().stream()
            .filter(key -> !RULE_KEYS.contains(key))
            .map(key -> path + ": unknown key " + key)
            .toList();
    return new Outcome<>(rule, Stream.concat(unknown.stream(), reader.errors().stream()).toList());
  }

  private static Rule readRule(FieldReader reader) {
    Rule defaults = Rule.defaults();
    return new Rule(
        reader.read("instruments", defaults.instruments(), raw -> instruments(raw)),
        reader.read("customer_types", defaults.customerTypes(), raw -> customerTypes(raw)),
        reader.read("currencies", defaults.currencies(), raw -> currencies(raw)),
        reader.read("providers", defaults.providers(), raw -> providers(raw)),
        reader.read("logs", defaults.logs(), raw -> logs(raw)),
        reader.read("local_only", defaults.localOnly(), raw -> localOnly(raw)),
        reader.read("retention_days", defaults.retentionDays(), raw -> retention(raw)));
  }

  private static Parsed<Set<Instrument>> instruments(Object raw) {
    return enumList(raw, Instrument::parse, INSTRUMENT_HELP);
  }

  private static Parsed<Set<CustomerType>> customerTypes(Object raw) {
    return enumList(raw, CustomerType::parse, CUSTOMER_HELP);
  }

  private static <T> Parsed<Set<T>> enumList(
      Object raw, Function<Object, Optional<T>> parse, String unknown) {
    return checked(raw, LIST_HELP, list -> enumValues(list, parse, unknown));
  }

  private static <T> Parsed<Set<T>> enumValues(
      List<?> names, Function<Object, Optional<T>> parse, String unknown) {
    List<Optional<T>> parsed = names.stream().map(parse).toList();
    return parsed.stream().allMatch(Optional::isPresent)
        ? Parsed.ok(
            parsed.stream().flatMap(Optional::stream).collect(Collectors.toUnmodifiableSet()))
        : Parsed.fail(unknown);
  }

  private static Parsed<Currencies> currencies(Object raw) {
    if ("all".equals(raw)) {
      return Parsed.ok(new Currencies.All());
    }
    return checked(raw, CURRENCIES_HELP, RuleParser::currencyCodes);
  }

  private static Parsed<Currencies> currencyCodes(List<?> codes) {
    return codes.stream().allMatch(code -> matches(code, CURRENCY))
        ? Parsed.ok(new Currencies.Only(Set.copyOf(asStrings(codes))))
        : Parsed.fail(CURRENCY_CODE_HELP);
  }

  private static Parsed<ProviderRule> providers(Object raw) {
    if ("all".equals(raw)) {
      return Parsed.ok(new ProviderRule.All());
    }
    return raw instanceof Map<?, ?> map
        ? providerList(Parsers.stringKeys(map))
        : Parsed.fail(PROVIDERS_HELP);
  }

  private static Parsed<ProviderRule> providerList(Map<String, Object> map) {
    return map.size() == 1
        ? providerKind(map.entrySet().iterator().next())
        : Parsed.fail(PROVIDERS_HELP);
  }

  private static Parsed<ProviderRule> providerKind(Map.Entry<String, Object> entry) {
    Function<Set<String>, ProviderRule> kind = PROVIDER_KINDS.get(entry.getKey());
    return kind != null && entry.getValue() instanceof List<?> ids
        ? Parsed.ok(kind.apply(Set.copyOf(asStrings(ids))))
        : Parsed.fail(PROVIDERS_HELP);
  }

  private static Parsed<List<String>> logs(Object raw) {
    return checked(raw, LOG_LIST_HELP, RuleParser::logNames);
  }

  private static Parsed<List<String>> logNames(List<?> names) {
    return names.stream().allMatch(name -> matches(name, LOG_NAME))
        ? Parsed.ok(asStrings(names))
        : Parsed.fail(LOG_NAME_HELP);
  }

  private static Parsed<Boolean> localOnly(Object raw) {
    return Parsers.bool(raw, BOOLEAN_HELP);
  }

  private static Parsed<Integer> retention(Object raw) {
    if (raw == null) {
      return Parsed.ok(null);
    }
    return raw instanceof Integer days && days > 0 ? Parsed.ok(days) : Parsed.fail(RETENTION_HELP);
  }

  /** A non-empty list, handed to {@code then}; anything else fails with {@code message}. */
  private static <T> Parsed<T> checked(
      Object raw, String message, Function<List<?>, Parsed<T>> then) {
    return raw instanceof List<?> list && !list.isEmpty() ? then.apply(list) : Parsed.fail(message);
  }

  private static boolean matches(Object value, Pattern pattern) {
    return value instanceof String text && pattern.matcher(text).matches();
  }

  private static List<String> asStrings(List<?> values) {
    return values.stream().map(Objects::toString).toList();
  }
}

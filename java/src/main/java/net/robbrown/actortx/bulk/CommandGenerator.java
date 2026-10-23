package net.robbrown.actortx.bulk;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.random.RandomGenerator;
import net.robbrown.actortx.service.PaymentCommand;

/**
 * Turns a {@link Plan} into one payment command at a time: an amount drawn in range, then each
 * setting either fixed or drawn from what the demo knows. Given the same random source it produces
 * the same commands.
 */
final class CommandGenerator {

  private static final List<String> CURRENCIES =
      List.of("USD", "EUR", "GBP", "INR", "CNY", "MXN", "BRL", "TRY", "SAR", "JPY", "KRW");
  private static final List<String> REGIONS = Arrays.asList(null, "UK", "EU", "IN", "CN");
  private static final Map<String, List<String>> CHOICES =
      Map.of(
          "merchant",
          Arrays.asList(null, "acme", "globex"),
          "customer",
          Arrays.asList(null, "vip-1"),
          "origin",
          REGIONS,
          "region",
          REGIONS,
          "instrument",
          List.of("spot", "forward"),
          "customer_type",
          List.of("business", "retail"),
          "source_currency",
          CURRENCIES,
          "destination_currency",
          CURRENCIES);

  /** Every setting a plan can randomise or fix, sorted. */
  static final List<String> SETTINGS = List.copyOf(new TreeSet<>(CHOICES.keySet()));

  // The source currency is drawn before the destination, which must differ from it.
  private static final List<String> DRAW_ORDER =
      List.of(
          "merchant",
          "customer",
          "origin",
          "region",
          "instrument",
          "customer_type",
          "source_currency",
          "destination_currency");

  private final Plan plan;
  private final RandomGenerator random;

  CommandGenerator(Plan plan, RandomGenerator random) {
    this.plan = plan;
    this.random = random;
  }

  /** The values a setting is drawn from ({@code null} means "not given"). */
  static List<String> choicesFor(String setting) {
    return CHOICES.get(setting);
  }

  /** The next payment: an amount in range, and every setting fixed or drawn. */
  PaymentCommand next() {
    BigDecimal amount = amount();
    Map<String, String> chosen = new HashMap<>();
    for (String key : DRAW_ORDER) {
      String value = valueOf(key, chosen);
      if (value != null) {
        chosen.put(key, value);
      }
    }
    return command(amount, chosen);
  }

  private BigDecimal amount() {
    long span = plan.maxMinor() - plan.minMinor() + 1;
    return BigDecimal.valueOf(plan.minMinor() + random.nextLong(span), 2);
  }

  private String valueOf(String key, Map<String, String> chosen) {
    boolean drawn = plan.mode() == Plan.Mode.RANDOM && plan.randomise().contains(key);
    return drawn ? pick(options(key, chosen)) : plan.fixed().get(key);
  }

  private String pick(List<String> options) {
    return options.get(random.nextInt(options.size()));
  }

  /** A payment converts one currency into a different one. */
  private static List<String> options(String key, Map<String, String> chosen) {
    if (key.equals("destination_currency")) {
      List<String> others = new ArrayList<>(CURRENCIES);
      others.remove(chosen.get("source_currency"));
      return others;
    }
    return CHOICES.get(key);
  }

  private static PaymentCommand command(BigDecimal amount, Map<String, String> chosen) {
    return new PaymentCommand(
        amount,
        chosen.get("merchant"),
        chosen.get("customer"),
        chosen.get("origin"),
        chosen.get("region"),
        chosen.get("instrument"),
        chosen.get("customer_type"),
        chosen.get("source_currency"),
        chosen.get("destination_currency"),
        null);
  }
}

package net.robbrown.actortx.tooling;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.robbrown.actortx.events.EventSink;
import net.robbrown.actortx.money.Money;
import net.robbrown.actortx.provider.FakeProvider;
import net.robbrown.actortx.provider.Health;
import net.robbrown.actortx.provider.HealthConfig;
import net.robbrown.actortx.provider.Limits;
import net.robbrown.actortx.provider.ProviderServer;
import net.robbrown.actortx.provider.Stats;
import net.robbrown.actortx.selection.Participant;
import net.robbrown.actortx.service.InvalidInputException;
import net.robbrown.actortx.service.ProviderCatalog;
import net.robbrown.actortx.service.ProviderControl;
import net.robbrown.actortx.service.ProviderView;
import net.robbrown.actortx.tooling.ProviderPresets.Base;
import net.robbrown.actortx.tooling.ProviderPresets.Preset;
import net.robbrown.actortx.tooling.ProviderPresets.Target;

/**
 * The demo world: five fake providers with different prices, speeds and currencies, behind real
 * provider servers. Tooling only (it is the stand-in for real providers).
 */
public final class DemoWorld implements ProviderCatalog, ProviderControl, AutoCloseable {

  private record Spec(
      String id, String rate, String fee, long latencyMs, List<String> currencies) {}

  private static final List<Spec> SPECS =
      List.of(
          new Spec("northwind", "1.0820", "1.5", 700, List.of("USD", "EUR", "GBP", "INR")),
          new Spec(
              "bluebird",
              "1.0790",
              "0.5",
              250,
              List.of("USD", "EUR", "GBP", "JPY", "KRW", "MXN", "BRL")),
          new Spec(
              "cobalt",
              "1.0870",
              "2.5",
              450,
              List.of("USD", "EUR", "GBP", "INR", "SAR", "TRY", "MXN", "BRL")),
          new Spec("meridian", "1.0760", "0.0", 500, List.of("CNY", "USD", "EUR", "JPY", "KRW")),
          new Spec("zenith", "1.0810", "1.0", 900, List.of("USD", "EUR", "GBP", "CNY", "JPY")));

  /** Market value of one unit of each currency in US dollars: the demo's price list. */
  private static final Map<String, Double> USD_PER_UNIT = usdPerUnit();

  private static final long REFERENCE_RATE = Money.rate("1.0820");

  private final Map<String, FakeProvider> fakes = new LinkedHashMap<>();
  private final Map<String, ProviderServer> servers = new LinkedHashMap<>();
  private final Map<String, List<String>> currencies = new HashMap<>();
  private final Map<String, Base> bases = new HashMap<>();
  private final Map<String, Preset> presets = ProviderPresets.all();
  private final double pace;

  /**
   * Builds the world.
   *
   * @param pace provider speed: 1.0 is human-watchable, smaller is faster
   * @param events where providers and their servers narrate
   */
  public DemoWorld(double pace, EventSink events) {
    this.pace = pace;
    Map<String, Long> mids = pairMids();
    for (Spec spec : SPECS) {
      FakeProvider fake =
          new FakeProvider(spec.id(), events)
              .rate(spec.rate())
              .fee(spec.fee())
              .latencyMs(Math.round(spec.latencyMs() * pace))
              .pairs(mids, REFERENCE_RATE);
      fakes.put(spec.id(), fake);
      servers.put(spec.id(), server(spec.id(), fake, events));
      currencies.put(spec.id(), spec.currencies());
      bases.put(spec.id(), new Base(spec.latencyMs(), spec.rate(), spec.fee(), "1"));
    }
  }

  private static ProviderServer server(String id, FakeProvider fake, EventSink events) {
    HealthConfig health =
        HealthConfig.defaults()
            .withFailureThreshold(3)
            .withCoolDownMs(8_000)
            .withSlowMs(1_500)
            .withRecoverySuccesses(3);
    return new ProviderServer(
        id, fake, health, Limits.defaults(), events, System::currentTimeMillis);
  }

  private static Map<String, Double> usdPerUnit() {
    Map<String, Double> prices = new LinkedHashMap<>();
    prices.put("USD", 1.0);
    prices.put("EUR", 1.08);
    prices.put("GBP", 1.27);
    prices.put("INR", 0.012);
    prices.put("CNY", 0.138);
    prices.put("MXN", 0.055);
    prices.put("BRL", 0.19);
    prices.put("TRY", 0.029);
    prices.put("SAR", 0.267);
    prices.put("JPY", 0.0067);
    prices.put("KRW", 0.00073);
    return prices;
  }

  // The market rate of every currency pair, as scaled integers.
  private static Map<String, Long> pairMids() {
    Map<String, Long> mids = new HashMap<>();
    USD_PER_UNIT.forEach(
        (from, a) ->
            USD_PER_UNIT.forEach(
                (to, b) -> {
                  if (!from.equals(to)) {
                    mids.put(from + "/" + to, Money.rate(round8(a / b)));
                  }
                }));
    return mids;
  }

  private static String round8(double value) {
    return BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_EVEN).toPlainString();
  }

  /** The pace the world was built with. */
  public double pace() {
    return pace;
  }

  @Override
  public List<Participant> participants() {
    return servers.entrySet().stream()
        .map(e -> new Participant(e.getKey(), e.getValue(), Set.copyOf(currencies.get(e.getKey()))))
        .toList();
  }

  @Override
  public List<ProviderServer> servers() {
    return List.copyOf(servers.values());
  }

  @Override
  public List<ProviderView> views() {
    return servers.keySet().stream().map(this::view).toList();
  }

  private ProviderView view(String id) {
    ProviderServer server = servers.get(id);
    FakeProvider fake = fakes.get(id);
    Health health = server.health();
    Stats stats = health.stats();
    return new ProviderView(
        id,
        health.state().wire(),
        health.breaker().name().toLowerCase(java.util.Locale.ROOT),
        stats.errorRate(),
        stats.p95Ms(),
        stats.samples(),
        server.inFlight(),
        health.version(),
        health.recovering(),
        Money.rateToDouble(fake.currentRate()),
        Money.toMajor(fake.currentFee()),
        fake.currentLatencyMs(),
        currencies.get(id));
  }

  @Override
  public Map<String, Object> apply(String providerId, String action, Map<String, Object> params) {
    if (!fakes.containsKey(providerId)) {
      throw new InvalidInputException(List.of("unknown provider " + providerId));
    }
    Preset preset = presets.get(action);
    if (preset == null) {
      throw new InvalidInputException(List.of("unknown action " + action));
    }
    preset.apply(
        new Target(
            providerId,
            fakes.get(providerId),
            servers.get(providerId),
            bases.get(providerId),
            pace,
            params));
    return Map.of("provider", providerId, "change", action);
  }

  @Override
  public void close() {
    servers.values().forEach(ProviderServer::close);
  }
}

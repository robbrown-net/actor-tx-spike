package net.robbrown.actortx.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import net.robbrown.actortx.events.EventSink;
import net.robbrown.actortx.policy.Policy;
import net.robbrown.actortx.provider.FakeProvider;
import net.robbrown.actortx.provider.HealthConfig;
import net.robbrown.actortx.provider.HealthState;
import net.robbrown.actortx.provider.Limits;
import net.robbrown.actortx.provider.Mode;
import net.robbrown.actortx.provider.Operation;
import net.robbrown.actortx.provider.ProviderServer;
import net.robbrown.actortx.provider.QuoteRequest;
import net.robbrown.actortx.selection.Excluded;
import net.robbrown.actortx.selection.Participant;
import net.robbrown.actortx.selection.QuoteSelector;
import net.robbrown.actortx.selection.Selection;
import net.robbrown.actortx.selection.Selection.Outcome;
import net.robbrown.actortx.validation.Validated;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A world of mixed providers: selection adapts as providers decay and drift. */
class SelectionWorldIT {

  private static final QuoteRequest REQUEST = QuoteRequest.of("fx-1", 100_000L);

  private final QuoteSelector selector = new QuoteSelector(EventSink.NONE);
  private FakeProvider cheap;
  private FakeProvider fast;
  private FakeProvider pricey;
  private FakeProvider flaky;
  private FakeProvider drifter;
  private List<ProviderServer> servers;
  private List<Participant> world;

  @BeforeEach
  void setUp() {
    cheap = new FakeProvider("cheap").rate("1.052").latencyMs(25);
    fast = new FakeProvider("fast").rate("1.048");
    pricey = new FakeProvider("pricey").rate("1.040").fee("5.0");
    flaky = new FakeProvider("flaky").rate("1.060").latencyMs(500);
    drifter = new FakeProvider("drifter").rate("1.070");
    HealthConfig health =
        HealthConfig.defaults().withFailureThreshold(2).withCoolDownMs(10_000).withSlowMs(80);
    servers =
        List.of(cheap, fast, pricey, flaky, drifter).stream()
            .map(
                f ->
                    new ProviderServer(
                        f.id(),
                        f,
                        health,
                        Limits.defaults(),
                        EventSink.NONE,
                        System::currentTimeMillis))
            .toList();
    world = servers.stream().map(s -> Participant.of(s.id(), s)).toList();
  }

  @AfterEach
  void tearDown() {
    servers.forEach(ProviderServer::close);
    selector.close();
  }

  private static Policy policy(Map<String, Object> weights) {
    return ((Validated.Valid<Policy>) Policy.fromMap(Map.of("weights", weights))).value();
  }

  private String winner(Policy policy) {
    Selection selection = selector.select(REQUEST, world, policy, 120, null);
    return ((Outcome.Selected) selection.outcome()).best().providerId();
  }

  private ProviderServer server(String id) {
    return servers.stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
  }

  @Test
  void selectionAdaptsAsProvidersDecayAndDrift() {
    Policy costLed = policy(Map.of("cost", 0.95, "speed", 0.05));
    Policy speedLed = policy(Map.of("cost", 0.2, "speed", 0.8));

    // Round 1: flaky misses the deadline (and its best-priced rate is wasted).
    Selection first = selector.select(REQUEST, world, costLed, 120, null);
    assertThat(((Outcome.Selected) first.outcome()).best().providerId()).isEqualTo("drifter");
    assertThat(first.excluded()).contains(new Excluded("flaky", "timeout"));

    // A merchant that values speed gets a different route from the same market.
    assertThat(winner(speedLed)).isEqualTo("drifter");

    // drifter silently changes version; the sentinel notices; it is quarantined.
    server("drifter").probe(Duration.ofSeconds(1));
    drifter.version("v2");
    server("drifter").probe(Duration.ofSeconds(1));
    assertThat(server("drifter").state()).isEqualTo(HealthState.QUARANTINED);

    Selection second = selector.select(REQUEST, world, costLed, 120, null);
    assertThat(((Outcome.Selected) second.outcome()).best().providerId()).isEqualTo("cheap");
    assertThat(second.excluded()).contains(new Excluded("drifter", "state: quarantined"));
    assertThat(winner(speedLed)).isEqualTo("fast");

    // flaky keeps timing out; the breaker opens and it stops being called at all.
    await()
        .atMost(Duration.ofSeconds(2))
        .until(
            () ->
                List.of(HealthState.DEGRADED, HealthState.STALLED)
                    .contains(server("flaky").state()));
    selector.select(REQUEST, world, costLed, 120, null);
    await()
        .atMost(Duration.ofSeconds(2))
        .until(() -> server("flaky").state() == HealthState.STALLED);

    Selection third = selector.select(REQUEST, world, costLed, 120, null);
    assertThat(third.excluded()).contains(new Excluded("flaky", "state: stalled"));
    assertThat(third.elapsedMs()).isLessThan(120);
  }

  @Test
  void whenEveryProviderIsUnusableTheBaselineRouteIsReturned() {
    List.of(cheap, fast, pricey, flaky, drifter)
        .forEach(f -> f.script(Operation.REQUEST_QUOTE, Mode.TIMEOUT));

    Selection selection = selector.select(REQUEST, world, Policy.defaults(), 50, "route:cheap");

    assertThat(selection.outcome()).isEqualTo(new Outcome.Fallback("route:cheap"));
    assertThat(selection.ranked()).isEmpty();
    assertThat(selection.excluded()).hasSize(5);
  }
}

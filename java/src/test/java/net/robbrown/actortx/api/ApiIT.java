package net.robbrown.actortx.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** The whole service, started for real, driven over HTTP: quote-and-select end to end. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"actortx.demo.pace=0.05", "server.address=127.0.0.1"})
class ApiIT {

  @Autowired private TestRestTemplate http;

  private Map<String, Object> select(Map<String, Object> body) {
    ResponseEntity<Map<String, Object>> response = Json.post(http, "/api/select", body);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    return response.getBody();
  }

  @Test
  void theServiceIsUpAndHealthy() {
    ResponseEntity<Map<String, Object>> health = Json.get(http, "/actuator/health");

    assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(health.getBody()).containsEntry("status", "UP");
  }

  @Test
  void fiveProvidersWithDifferentPricesAndCurrenciesAreListed() {
    List<Map<String, Object>> providers = Json.list(http, "/api/providers");

    assertThat(providers)
        .extracting(p -> p.get("id"))
        .containsExactly("northwind", "bluebird", "cobalt", "meridian", "zenith");
    Map<String, Object> meridian = providers.get(3);
    assertThat(Json.strings(meridian.get("currencies")))
        .containsExactly("CNY", "USD", "EUR", "JPY", "KRW");
    assertThat(meridian).containsEntry("state", "unknown").containsEntry("breaker", "closed");
  }

  @Test
  void aDollarsToRupeesPaymentIsPricedFromTheMarketAndOnlyCarriersAreAsked() {
    Map<String, Object> result =
        select(
            Map.of(
                "amount",
                100,
                "source_currency",
                "USD",
                "destination_currency",
                "INR",
                "deadline_ms",
                1000));

    assertThat(result).containsEntry("outcome", "selected");
    assertThat(List.of("northwind", "cobalt")).contains((String) result.get("provider"));
    List<Map<String, Object>> excluded = Json.maps(result.get("excluded"));
    assertThat(excluded)
        .extracting(e -> e.get("provider"))
        .containsExactlyInAnyOrder("bluebird", "meridian", "zenith");
    assertThat(excluded)
        .allSatisfy(e -> assertThat(e).containsEntry("reason", "unsupported: USD/INR"));
    // market USD/INR is 83.33; a provider's spread moves it by well under 1%
    double rate = Double.parseDouble((String) Json.maps(result.get("ranked")).get(0).get("rate"));
    assertThat(rate).isBetween(82.0, 85.0);
  }

  @Test
  void aPaymentNoProviderCarriesHasNoQuote() {
    Map<String, Object> result =
        select(
            Map.of(
                "amount",
                10,
                "source_currency",
                "TRY",
                "destination_currency",
                "KRW",
                "deadline_ms",
                500));

    assertThat(result).containsEntry("outcome", "none");
    assertThat(Json.maps(result.get("ranked"))).isEmpty();
  }

  @Test
  void aPolicyThatIsNotValidIsRefusedWithTheReasons() {
    ResponseEntity<Map<String, Object>> response =
        Json.post(http, "/api/select", Map.of("policy", Map.of("weights", Map.of("cost", -1))));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(response.getBody()).containsEntry("ok", false);
    assertThat(String.valueOf(response.getBody().get("error"))).contains("weights");
  }

  @Test
  void jurisdictionForbidsAForwardToIndiaAndACurrencyChinaDoesNotAllow() {
    Map<String, Object> forward =
        Json.post(
                http,
                "/api/eligibility",
                Map.of("destinationRegion", "IN", "instrument", "forward"))
            .getBody();
    assertThat(forward).containsEntry("permitted", false);
    assertThat(Json.strings(forward.get("reasons")))
        .containsExactly("forward is not permitted in IN");

    Map<String, Object> inr =
        Json.post(
                http,
                "/api/eligibility",
                Map.of(
                    "destinationRegion",
                    "CN",
                    "sourceCurrency",
                    "INR",
                    "destinationCurrency",
                    "USD"))
            .getBody();
    assertThat(Json.strings(inr.get("reasons"))).containsExactly("INR cannot be used in CN");

    Map<String, Object> ok =
        Json.post(http, "/api/eligibility", Map.of("destinationRegion", "IN", "instrument", "spot"))
            .getBody();
    assertThat(ok).containsEntry("permitted", true);
    assertThat(Json.strings(ok.get("logs"))).containsExactly("IN");
  }

  private void heal(String provider) {
    Json.post(http, "/api/control", Map.of("action", "provider", "id", provider, "do", "heal"));
  }

  private void control(String provider, String preset) {
    ResponseEntity<Map<String, Object>> response =
        Json.post(http, "/api/control", Map.of("action", "provider", "id", provider, "do", preset));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).containsEntry("ok", true);
  }

  @AfterEach
  void healEveryone() {
    List.of("northwind", "bluebird", "cobalt", "meridian", "zenith").forEach(this::heal);
  }

  @Test
  void anOperatorCanBreakAProviderAndTheSelectionRoutesAroundIt() {
    control("bluebird", "reject");
    control("cobalt", "error");

    Map<String, Object> result = select(Map.of("amount", 100, "deadline_ms", 1000));

    List<Map<String, Object>> excluded = Json.maps(result.get("excluded"));
    assertThat(excluded)
        .extracting(e -> e.get("provider") + ": " + e.get("reason"))
        .contains("bluebird: rejected: no_liquidity", "cobalt: error: unavailable");
    assertThat(result.get("provider")).isNotIn("bluebird", "cobalt");
  }

  @Test
  void repeatedFailuresOpenTheBreakerAndTheProviderIsThenNotAsked() {
    control("zenith", "error");
    for (int i = 0; i < 3; i++) {
      select(Map.of("amount", 10, "deadline_ms", 500));
    }

    Map<String, Object> zenith =
        Json.list(http, "/api/providers").stream()
            .filter(p -> p.get("id").equals("zenith"))
            .findFirst()
            .orElseThrow();
    assertThat(zenith).containsEntry("state", "stalled").containsEntry("breaker", "open");

    List<Map<String, Object>> excluded =
        Json.maps(select(Map.of("amount", 10, "deadline_ms", 500)).get("excluded"));
    assertThat(excluded)
        .anySatisfy(
            e ->
                assertThat(e)
                    .containsEntry("provider", "zenith")
                    .containsEntry("reason", "state: stalled"));
  }

  @Test
  void aSlowProviderCanBeStalledRepricedAndHealed() {
    control("northwind", "stall");
    ResponseEntity<Map<String, Object>> noRate =
        Json.post(
            http, "/api/control", Map.of("action", "provider", "id", "meridian", "do", "reprice"));
    assertThat(noRate.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(String.valueOf(noRate.getBody().get("error")))
        .isEqualTo("reprice needs a numeric rate");
    ResponseEntity<Map<String, Object>> reprice =
        Json.post(
            http,
            "/api/control",
            Map.of("action", "provider", "id", "meridian", "do", "reprice", "rate", 1.5));
    assertThat(reprice.getStatusCode()).isEqualTo(HttpStatus.OK);

    Map<String, Object> meridian =
        Json.list(http, "/api/providers").stream()
            .filter(p -> p.get("id").equals("meridian"))
            .findFirst()
            .orElseThrow();
    assertThat(meridian).containsEntry("rate", 1.5);

    heal("meridian");
    Map<String, Object> healed =
        Json.list(http, "/api/providers").stream()
            .filter(p -> p.get("id").equals("meridian"))
            .findFirst()
            .orElseThrow();
    assertThat(healed).containsEntry("rate", 1.076);
  }

  @Test
  void everyPresetIsAccepted() {
    List.of(
            "slow",
            "stall",
            "error",
            "reject",
            "authorise_fails",
            "pay_answer_lost",
            "pay_vanishes",
            "settle_fails",
            "settle_vanishes",
            "drift",
            "release",
            "heal")
        .forEach(preset -> control("zenith", preset));
  }

  @Test
  void anUnknownProviderOrPresetOrActionIsRefused() {
    assertThat(
            Json.post(
                    http,
                    "/api/control",
                    Map.of("action", "provider", "id", "nobody", "do", "heal"))
                .getStatusCode())
        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(
            Json.post(
                    http,
                    "/api/control",
                    Map.of("action", "provider", "id", "zenith", "do", "explode"))
                .getStatusCode())
        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(Json.post(http, "/api/control", Map.of("action", "dance")).getStatusCode())
        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
  }

  @Test
  void aMerchantsObjectivesChangeTheRoutingAndTheResponseSaysWhichLayersApplied() {
    assertThat(select(Map.of("amount", 100, "deadline_ms", 1000)).get("policy"))
        .isEqualTo("global");
    assertThat(
            select(Map.of("amount", 100, "merchant_id", "acme", "deadline_ms", 1000)).get("policy"))
        .isEqualTo("global + merchant:acme");
    assertThat(
            select(Map.of("amount", 100, "merchant_id", "globex", "deadline_ms", 1000))
                .get("policy"))
        .isEqualTo("global + merchant:globex (ignored: not_entitled)");
    assertThat(
            select(
                    Map.of(
                        "amount",
                        100,
                        "merchant_id",
                        "acme",
                        "customer_id",
                        "vip-1",
                        "deadline_ms",
                        1000))
                .get("policy"))
        .isEqualTo("global + merchant:acme + customer:vip-1");
  }
}

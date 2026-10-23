package net.robbrown.actortx.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The whole service: a payment sent over HTTP, run through real providers, recorded on disk. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"actortx.demo.pace=0.02", "server.address=127.0.0.1"})
class PaymentIT {

  @TempDir static Path audit;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("actortx.audit.dir", () -> audit.toString());
  }

  @Autowired private TestRestTemplate http;
  @LocalServerPort private int port;

  private Map<String, Object> pay(Map<String, Object> body) {
    ResponseEntity<Map<String, Object>> accepted = Json.post(http, "/api/payments", body);
    assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    String id = (String) accepted.getBody().get("id");
    ResponseEntity<Map<String, Object>> done =
        Json.get(http, "/api/payments/" + id + "/result?timeout_ms=15000");
    assertThat(done.getStatusCode()).isEqualTo(HttpStatus.OK);
    return done.getBody();
  }

  @Test
  void aPaymentCompletesAndEveryStepIsOnTheRecord() throws IOException {
    Map<String, Object> done = pay(Map.of("amount", 250, "merchant_id", "acme"));

    Map<String, Object> outcome = Json.map(done.get("outcome"));
    assertThat(outcome).containsEntry("status", "completed");
    assertThat(Json.map(outcome.get("summary")).get("steps"))
        .isEqualTo(List.of("accept_quote", "authorise", "pay", "settle"));
    assertThat(allLogLines()).anyMatch(l -> l.contains("\"kind\":\"tx_completed\""));
  }

  @Test
  void aPaymentThatJurisdictionForbidsFailsBeforeAnyMoneyMoves() {
    Map<String, Object> done =
        pay(Map.of("amount", 10, "destination_region", "IN", "instrument", "forward"));

    assertThat(Json.map(done.get("outcome"))).containsEntry("status", "failed");
    assertThat(Json.map(done.get("outcome")).get("reason").toString()).startsWith("not_permitted");
  }

  @Test
  void badInputIs422() {
    ResponseEntity<Map<String, Object>> response = Json.post(http, "/api/payments", Map.of());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
  }

  private static List<String> allLogLines() throws IOException {
    try (Stream<Path> files = Files.list(audit)) {
      return files
          .filter(f -> f.toString().endsWith(".log"))
          .flatMap(
              f -> {
                try {
                  return Files.readAllLines(f).stream();
                } catch (IOException unreadable) {
                  throw new IllegalStateException(unreadable);
                }
              })
          .toList();
    }
  }

  @Test
  void aGuaranteedMerchantsPaymentIsJudgedAndAppearsInTheLedgers() {
    Map<String, Object> done = pay(Map.of("amount", 1000, "merchant_id", "acme"));

    Map<String, Object> summary = Json.map(Json.map(done.get("outcome")).get("summary"));
    assertThat(Json.map(summary.get("guarantee"))).containsEntry("merchant_id", "acme");
    assertThat(Json.list(http, "/api/guarantees")).isNotEmpty();
    assertThat(Json.map(Json.map(Json.get(http, "/api/economics").getBody()).get("overall")))
        .containsKey("transactions");
    assertThat(Json.list(http, "/api/events?limit=5")).hasSizeLessThanOrEqualTo(5);
  }

  @Test
  void aCutOffThatPassesStopsPaymentsToThatRegion() {
    Map<String, Object> set =
        Json.post(
                http,
                "/api/control",
                Map.of("action", "cutoff", "region", "IN", "in_seconds", 0.001))
            .getBody();
    assertThat(set).containsEntry("ok", true);
    assertThat(Json.get(http, "/api/cutoffs").getBody()).containsKey("IN");

    Map<String, Object> done = pay(Map.of("amount", 10, "destination_region", "IN"));

    assertThat(Json.map(done.get("outcome"))).containsEntry("status", "failed");
    assertThat(Json.map(done.get("outcome")).get("reason").toString())
        .startsWith("deadline_missed: IN");
    Json.post(http, "/api/control", Map.of("action", "cutoff", "region", "IN"));
  }

  @Test
  void theEventStreamCarriesWhatHappens() throws Exception {
    HttpClient client = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/stream")).build();
    CompletableFuture<HttpResponse<Stream<String>>> response =
        client.sendAsync(request, HttpResponse.BodyHandlers.ofLines());
    Thread.sleep(300);

    pay(Map.of("amount", 5));

    HttpResponse<Stream<String>> stream = response.get(10, TimeUnit.SECONDS);
    assertThat(stream.body().filter(l -> l.startsWith("event:")).findFirst()).isPresent();
  }

  @Test
  void theDurableLogReplaysCleanAgainstTheRules() {
    pay(Map.of("amount", 75, "merchant_id", "acme"));

    Map<String, Object> report = Json.get(http, "/api/replay").getBody();

    assertThat(report).containsEntry("available", true);
    assertThat(Json.map(report.get("residency"))).containsEntry("ok", true);
    assertThat(Json.maps(report.get("logs"))).isNotEmpty();
    assertThat(Json.maps(report.get("logs")))
        .allSatisfy(l -> assertThat(l).containsEntry("ok", true));
  }

  @Test
  void theDashboardPageAndItsViewsAreServed() {
    String page = http.getForObject("/", String.class);

    assertThat(page).contains("<title>");
    assertThat(Json.get(http, "/api/snapshot").getBody()).containsKeys("providers", "admission");
    // The page maps over these on every frame; a missing one stops it drawing at all.
    assertThat(Json.maps(Json.get(http, "/api/snapshot").getBody().get("providers")))
        .allSatisfy(
            p ->
                assertThat(p)
                    .containsKeys(
                        "id",
                        "state",
                        "breaker",
                        "error_rate",
                        "p95_ms",
                        "samples",
                        "in_flight",
                        "version",
                        "recovering",
                        "rate",
                        "fee",
                        "latency_ms",
                        "currencies",
                        "faults"));
    assertThat(Json.get(http, "/api/metrics").getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(Json.get(http, "/api/lab").getBody()).containsEntry("available", false);
  }

  @Test
  void aSettlementThatFailsWaitsForAPersonWhoChecksAndResolvesIt() {
    List<String> ids =
        Json.list(http, "/api/providers").stream().map(p -> (String) p.get("id")).toList();
    ids.forEach(id -> control(Map.of("action", "provider", "id", id, "do", "settle_fails")));
    try {
      Map<String, Object> done = pay(Map.of("amount", 20));
      assertThat(Json.map(done.get("outcome"))).containsEntry("status", "needs_review");
      review((String) done.get("id"));
    } finally {
      ids.forEach(id -> control(Map.of("action", "provider", "id", id, "do", "heal")));
    }
  }

  // The transaction appears in the queue; a person checks it and resolves it.
  private void review(String tx) {
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(Json.maps(Json.get(http, "/api/review").getBody().get("entries")))
                    .anyMatch(e -> tx.equals(e.get("tx")) && "open".equals(e.get("status"))));
    assertThat(control(Map.of("action", "review", "do", "check", "tx", tx, "by", "rob")))
        .containsEntry("ok", true);
    Map<String, Object> resolve =
        Map.of(
            "action",
            "review",
            "do",
            "resolve",
            "tx",
            tx,
            "by",
            "rob",
            "outcome",
            "paid",
            "note",
            "settled by hand");
    assertThat(control(resolve)).containsEntry("ok", true);
  }

  @Test
  void aBatchOfPaymentsIsSentAndFinishes() {
    Map<String, Object> started =
        control(
            Map.of(
                "action",
                "bulk",
                "do",
                "start",
                "count",
                6,
                "min_amount",
                "5",
                "max_amount",
                "50",
                "interval_ms",
                5));
    assertThat(started).containsEntry("ok", true);

    await()
        .atMost(Duration.ofSeconds(20))
        .untilAsserted(
            () ->
                assertThat(Json.get(http, "/api/bulk").getBody())
                    .containsEntry("status", "finished"));
    assertThat(Json.get(http, "/api/bulk").getBody()).containsEntry("submitted", 6);
    control(Map.of("action", "bulk", "do", "clear"));
  }

  @Test
  void anAdmissionLimitCanBeSetAndRemoved() {
    Map<String, Object> set = control(Map.of("action", "admission", "max_in_flight", 50));
    assertThat(Json.map(set.get("result"))).containsEntry("limit", 50);
    control(Map.of("action", "admission", "max_in_flight", "none"));
    assertThat(control(Map.of("action", "submit", "amount", 12))).containsEntry("ok", true);
  }

  private Map<String, Object> control(Map<String, Object> command) {
    ResponseEntity<Map<String, Object>> response = Json.post(http, "/api/control", command);
    assertThat(response.getStatusCode())
        .as(String.valueOf(response.getBody()))
        .isEqualTo(HttpStatus.OK);
    return response.getBody();
  }
}

package net.robbrown.actortx.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
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
}

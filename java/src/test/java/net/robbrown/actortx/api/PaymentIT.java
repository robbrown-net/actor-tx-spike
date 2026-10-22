package net.robbrown.actortx.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
}

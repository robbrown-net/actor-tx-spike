package net.robbrown.actortx.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

/** The sentinel running on its own schedule: a provider that drifts is found and quarantined. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"actortx.demo.pace=0.05", "actortx.sentinel.interval-ms=100"})
class SentinelIT {

  @Autowired private TestRestTemplate http;

  private String stateOf(String provider) {
    return Json.list(http, "/api/providers").stream()
        .filter(p -> p.get("id").equals(provider))
        .map(p -> (String) p.get("state"))
        .findFirst()
        .orElseThrow();
  }

  private void control(String provider, String preset) {
    Json.post(http, "/api/control", Map.of("action", "provider", "id", provider, "do", preset));
  }

  @Test
  void aProviderThatChangesVersionIsQuarantinedBySentinelAndLeftOutUntilReleased() {
    await().atMost(Duration.ofSeconds(5)).until(() -> stateOf("bluebird").equals("healthy"));

    control("bluebird", "drift");
    await().atMost(Duration.ofSeconds(5)).until(() -> stateOf("bluebird").equals("quarantined"));

    List<Map<String, Object>> excluded =
        Json.maps(
            Json.post(http, "/api/select", Map.of("amount", 10, "deadline_ms", 500))
                .getBody()
                .get("excluded"));
    assertThat(excluded)
        .anySatisfy(
            e ->
                assertThat(e)
                    .containsEntry("provider", "bluebird")
                    .containsEntry("reason", "state: quarantined"));

    control("bluebird", "release");
    await().atMost(Duration.ofSeconds(5)).until(() -> !stateOf("bluebird").equals("quarantined"));
  }
}

package net.robbrown.actortx.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerfHistoryControllerTest {

  @TempDir Path dir;

  @Test
  void anAbsentFileIsAnEmptyHistory() throws IOException {
    PerfHistoryController controller = controllerFor(dir.resolve("none.json"));

    assertThat(controller.history().isArray()).isTrue();
    assertThat(controller.history().size()).isZero();
  }

  @Test
  void itServesTheRecordsInTheFile() throws IOException {
    Path file = dir.resolve("results.json");
    Files.writeString(file, "[{\"version\":\"0.2\",\"runtime\":\"Java\",\"tps\":310.5}]");

    JsonNode history = controllerFor(file).history();

    assertThat(history.size()).isEqualTo(1);
    assertThat(history.get(0).get("version").asText()).isEqualTo("0.2");
    assertThat(history.get(0).get("tps").asDouble()).isEqualTo(310.5);
  }

  @Test
  void aFileThatIsNotAListIsAnEmptyHistory() throws IOException {
    Path file = dir.resolve("results.json");
    Files.writeString(file, "{\"not\":\"a list\"}");

    assertThat(controllerFor(file).history().size()).isZero();
  }

  private static PerfHistoryController controllerFor(Path file) {
    return new PerfHistoryController(file.toString(), new ObjectMapper());
  }
}

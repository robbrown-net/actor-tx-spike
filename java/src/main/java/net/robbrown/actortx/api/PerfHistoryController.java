package net.robbrown.actortx.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The release history the Performance tab charts: the records in Performance Analysis/results.json.
 */
@RestController
public class PerfHistoryController {

  private final Path file;
  private final ObjectMapper mapper;

  /**
   * Reads the history from {@code actortx.perf.history}, a path relative to the directory the
   * server runs in.
   */
  public PerfHistoryController(
      @Value("${actortx.perf.history:Performance Analysis/results.json}") String file,
      ObjectMapper mapper) {
    this.file = Path.of(file);
    this.mapper = mapper;
  }

  /** The records, or an empty list while the file does not exist or does not hold a JSON list. */
  @GetMapping("/api/perf-history")
  public JsonNode history() throws IOException {
    if (!Files.isRegularFile(file)) {
      return mapper.createArrayNode();
    }
    JsonNode records = mapper.readTree(file.toFile());
    return records.isArray() ? records : mapper.createArrayNode();
  }
}

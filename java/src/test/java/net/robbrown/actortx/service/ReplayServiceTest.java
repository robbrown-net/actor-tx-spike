package net.robbrown.actortx.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.robbrown.actortx.audit.AuditRouter;
import net.robbrown.actortx.events.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReplayServiceTest {

  @TempDir Path dir;

  @Test
  void withoutALogDirectoryThereIsNothingToReplay() {
    ReplayService service = new ReplayService(Optional.empty(), Optional::empty);

    assertThat(service.verify()).containsEntry("available", false);
  }

  @Test
  void aTransactionThatCompletedWithoutDoingAnythingBreaksTheRules() {
    try (AuditRouter router = AuditRouter.open(dir, List.of(), false)) {
      router.write(
          new Event(
              1,
              1_000,
              "t",
              "payment",
              "tx_received",
              "received",
              Map.of("amount", 100, "request", Map.of("id", "t", "amount", 100))));
      router.write(new Event(2, 1_010, "t", "payment", "tx_completed", "done", Map.of()));
      router.flush();
    }

    Map<String, Object> report = new ReplayService(Optional.of(dir), Optional::empty).verify();

    assertThat(report).containsEntry("available", true);
    List<?> logs = (List<?>) report.get("logs");
    Map<?, ?> global = (Map<?, ?>) logs.get(0);
    assertThat(global.get("log")).isEqualTo("GLOBAL");
    assertThat(global.get("ok")).isEqualTo(false);
    assertThat((List<?>) global.get("violations")).isNotEmpty();
    assertThat(((Map<?, ?>) report.get("residency")).get("ok")).isEqualTo(true);
  }
}

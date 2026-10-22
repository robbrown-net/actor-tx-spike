package net.robbrown.actortx.audit;

import static net.robbrown.actortx.audit.AuditFixtures.event;
import static net.robbrown.actortx.audit.AuditFixtures.received;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.robbrown.actortx.audit.AuditLog.Verification.Intact;
import net.robbrown.actortx.events.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The audit trail against real files with real fsyncs: restarts, tampering, lock, residency. */
class AuditIT {

  private static final List<String> REGIONS = List.of("IN", "US");

  @TempDir Path dir;

  private AuditRouter open() {
    return AuditRouter.open(dir, REGIONS, true);
  }

  private static void writeTransactions(AuditRouter router, long from) {
    router.write(received(from, "tx-in", "IN"));
    router.write(event(from + 1, "tx-in", "step_succeeded"));
    router.write(received(from + 2, "tx-both", "GLOBAL", "US"));
    router.write(event(from + 3, null, "health", "provider up", Map.of("provider", "alpha")));
    router.write(event(from + 4, "tx-in", "tx_costed"));
    router.write(event(from + 5, "tx-both", "tx_costed"));
  }

  private String read(String log) throws IOException {
    return Files.readString(dir.resolve(log + ".log"));
  }

  @Test
  void aRegionalTrailSurvivesRestartsAndEveryRegionVerifies() throws IOException {
    AuditLog.Head before;
    try (AuditRouter router = open()) {
      writeTransactions(router, 1);
      assertThat(router.flush()).isEmpty();
      before = router.heads().get("IN");
    }
    try (AuditRouter restarted = open()) {
      assertThat(restarted.heads().get("IN")).isEqualTo(before);
      writeTransactions(restarted, 100);
      assertThat(restarted.flush()).isEmpty();
    }
    for (String log : List.of("IN", "US", "GLOBAL")) {
      assertThat(AuditLog.verify(dir.resolve(log + ".log"))).isInstanceOf(Intact.class);
    }
    assertThat(read("US")).doesNotContain("tx-in");
    assertThat(read("IN")).contains("tx-in").doesNotContain("provider up");
  }

  @Test
  void aTamperedRegionRefusesToStartAndLeavesNoLockBehind() throws IOException {
    try (AuditRouter router = open()) {
      writeTransactions(router, 1);
    }
    Path in = dir.resolve("IN.log");
    Files.writeString(in, Files.readString(in).replace("step_succeeded", "step_failed"));
    assertThatThrownBy(this::open).isInstanceOf(AuditException.class);
    assertThat(dir.resolve("IN.log.lock")).doesNotExist();
    assertThat(dir.resolve("GLOBAL.log.lock")).doesNotExist();
  }

  @Test
  void aSecondRouterIsRefusedAndCardNumbersNeverReachDisk() throws IOException {
    try (AuditRouter router = open()) {
      assertThatThrownBy(this::open).isInstanceOf(AuditException.class);
      Event card = event(1, "tx-in", "step_failed", "paid 4111 1111 1111 1111", Map.of());
      router.write(received(0, "tx-in", "IN"));
      router.write(card);
      assertThat(router.flush()).isEmpty();
    }
    assertThat(read("IN")).doesNotContain("4111 1111 1111 1111").contains("[REDACTED-PAN]");
  }
}

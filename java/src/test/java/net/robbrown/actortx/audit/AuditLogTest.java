package net.robbrown.actortx.audit;

import static net.robbrown.actortx.audit.AuditFixtures.counting;
import static net.robbrown.actortx.audit.AuditFixtures.event;
import static net.robbrown.actortx.audit.AuditFixtures.failing;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.robbrown.actortx.audit.AuditLog.Head;
import net.robbrown.actortx.audit.AuditLog.Verification.Broken;
import net.robbrown.actortx.audit.AuditLog.Verification.Intact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditLogTest {

  private static final String GENESIS = "0".repeat(64);
  private static final Duration FIVE_SECONDS = Duration.ofSeconds(5);

  @TempDir Path dir;

  private Path path() {
    return dir.resolve("audit.log");
  }

  private AuditLog open() {
    return AuditLog.open(path(), false);
  }

  private void fill(AuditLog log, int count) {
    for (long n = 1; n <= count; n++) {
      log.write(event(n, "tx-1", "step_succeeded"));
    }
  }

  private List<String> lines() throws IOException {
    return Files.readAllLines(path());
  }

  private void writeLines(List<String> lines) throws IOException {
    Files.writeString(path(), String.join("\n", lines) + "\n");
  }

  @Test
  void recordsFormAChainThatVerifiesAndTheHeadSaysSo() {
    try (AuditLog log = open()) {
      fill(log, 5);
      Head head = log.head();
      assertThat(head.records()).isEqualTo(5);
      assertThat(head.hash()).hasSize(64);
      assertThat(AuditLog.verify(path())).isEqualTo(new Intact(head));
    }
  }

  @Test
  void anEmptyLogHasTheGenesisHeadAndAMissingFileVerifiesAsEmpty() {
    try (AuditLog log = open()) {
      assertThat(log.head()).isEqualTo(new Head(0, GENESIS));
    }
    assertThat(AuditLog.verify(dir.resolve("absent.log")))
        .isEqualTo(new Intact(new Head(0, GENESIS)));
  }

  @Test
  void eachRecordIsOneJsonLineWithTheElixirFields() throws IOException {
    try (AuditLog log = open()) {
      log.write(event(1, "tx-1", "tx_received", "hello", Map.of("k", 1)));
    }
    Map<String, Object> record = AuditChain.records(path()).getFirst();
    assertThat(record)
        .containsOnlyKeys(
            "n", "seq", "at", "tx", "actor", "kind", "message", "data", "prev", "hash")
        .containsEntry("n", 1)
        .containsEntry("tx", "tx-1")
        .containsEntry("prev", GENESIS);
    assertThat(lines()).hasSize(1);
  }

  @Test
  void editingARecordIsDetectedAtThatLine() throws IOException {
    try (AuditLog log = open()) {
      fill(log, 4);
    }
    List<String> lines = lines();
    lines.set(1, lines.get(1).replace("something happened", "nothing happened"));
    writeLines(lines);
    assertThat(AuditLog.verify(path())).isEqualTo(new Broken(2, "hash_mismatch"));
  }

  @Test
  void removingOrReorderingRecordsIsDetected() throws IOException {
    try (AuditLog log = open()) {
      fill(log, 4);
    }
    List<String> l = lines();
    writeLines(List.of(l.get(0), l.get(2), l.get(3)));
    assertThat(AuditLog.verify(path())).isEqualTo(new Broken(2, "out_of_sequence"));
    writeLines(List.of(l.get(0), l.get(1), l.get(1), l.get(3)));
    assertThat(AuditLog.verify(path())).isEqualTo(new Broken(3, "out_of_sequence"));
  }

  @Test
  void aRecordWhoseLinkToItsPredecessorIsWrongIsDetected() throws IOException {
    try (AuditLog log = open()) {
      fill(log, 2);
    }
    String firstHash = (String) AuditChain.records(path()).getFirst().get("hash");
    List<String> lines = lines();
    lines.set(1, lines.get(1).replace(firstHash, "a".repeat(64)));
    writeLines(lines);
    assertThat(AuditLog.verify(path())).isEqualTo(new Broken(2, "chain_broken"));
  }

  @Test
  void garbageNullArraysAndRecordsWithoutAHashAreMalformed() throws IOException {
    for (String line : List.of("not json", "", "null", "[1,2]", "{\"n\":1}")) {
      writeLines(List.of(line));
      assertThat(AuditLog.verify(path())).isEqualTo(new Broken(1, "malformed"));
    }
  }

  @Test
  void theChainResumesAcrossARestart() {
    try (AuditLog first = open()) {
      fill(first, 3);
    }
    try (AuditLog second = open()) {
      second.write(event(4, "tx-1", "step_succeeded"));
      assertThat(second.head().records()).isEqualTo(4);
    }
    assertThat(AuditLog.verify(path()))
        .isInstanceOfSatisfying(Intact.class, i -> assertThat(i.head().records()).isEqualTo(4));
  }

  @Test
  void itRefusesToStartOnATamperedLogAndReleasesTheLock() throws IOException {
    try (AuditLog log = open()) {
      fill(log, 3);
    }
    Files.writeString(path(), Files.readString(path()).replace("something happened", "x"));
    assertThatThrownBy(() -> open())
        .isInstanceOf(AuditException.class)
        .hasMessageContaining("fails verification at record 1: hash_mismatch");
    assertThat(dir.resolve("audit.log.lock")).doesNotExist();
  }

  @Test
  void structuredDataIsWrittenAsJsonAndSyncCanBeOff() throws IOException {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("nested", Map.of("k", List.of(1, 2)));
    data.put("missing", null);
    try (AuditLog log = open()) {
      log.write(event(1, "tx-1", "step_succeeded", "m", data));
    }
    assertThat(AuditLog.verify(path())).isInstanceOf(Intact.class);
    assertThat(Files.readString(path())).contains("\"kind\":\"step_succeeded\"");
  }

  @Test
  void cardNumbersAreRedactedBeforeAnythingReachesDisk() throws IOException {
    Map<String, Object> data =
        Map.of("note", "4012888888881881", "list", List.of("378282246310005"));
    try (AuditLog log = open()) {
      log.write(event(1, "tx-1", "step", "card 4111111111111111 paid", data));
    }
    String text = Files.readString(path());
    assertThat(text)
        .doesNotContain("4111111111111111", "4012888888881881", "378282246310005")
        .contains("[REDACTED-PAN]");
    assertThat(AuditLog.verify(path())).isInstanceOf(Intact.class);
  }

  @Test
  void theChainsOwnFieldsAreNeverScrubbed() throws IOException {
    try (AuditLog log = open()) {
      log.write(event(1, "4111111111111111", "step", "m", Map.of()));
    }
    assertThat(Files.readString(path())).contains("\"tx\":\"4111111111111111\"");
  }

  @Test
  void numbersOfEveryKindSurviveTheRoundTripIntact() {
    Map<String, Object> data =
        Map.of("amount", new BigDecimal("12.50"), "rate", 1.5d, "big", 1e10d);
    try (AuditLog log = open()) {
      log.write(event(1, "tx-1", "step", "m", data));
      log.write(event(2, "tx-1", "step", "m", data));
    }
    assertThat(AuditLog.verify(path()))
        .isInstanceOfSatisfying(Intact.class, i -> assertThat(i.head().records()).isEqualTo(2));
  }

  @Test
  void aValueJsonCannotHoldIsRefusedRatherThanRecordedWrongly() {
    try (AuditLog log = open()) {
      assertThatThrownBy(
              () -> log.write(event(1, "tx-1", "step", "m", Map.of("ratio", Double.NaN))))
          .isInstanceOf(AuditException.class);
    }
  }

  @Test
  void durableRecordsAreForcedOnAFlushOrOnTheTimer() {
    AtomicInteger forces = new AtomicInteger();
    try (AuditLog log = AuditLog.open(path(), true, counting(forces))) {
      fill(log, 2);
      assertThat(log.flush()).isEmpty();
      int afterFlush = forces.get();
      assertThat(afterFlush).isPositive();
      assertThat(log.flush()).isEmpty();
      assertThat(forces).hasValue(afterFlush);
      log.write(event(3, "tx-1", "step_succeeded"));
      await().atMost(FIVE_SECONDS).until(() -> forces.get() > afterFlush);
    }
  }

  @Test
  void withSyncOffNothingIsForcedButEverythingIsWritten() {
    AtomicInteger forces = new AtomicInteger();
    try (AuditLog log = AuditLog.open(path(), false, counting(forces))) {
      fill(log, 1);
      assertThat(log.flush()).isEmpty();
      assertThat(forces).hasValue(0);
    }
    assertThat(AuditLog.verify(path()))
        .isInstanceOfSatisfying(Intact.class, i -> assertThat(i.head().records()).isEqualTo(1));
  }

  @Test
  void aFailedSyncIsReportedOnceAndStaysFailed() {
    AtomicInteger attempts = new AtomicInteger();
    try (AuditLog log = AuditLog.open(path(), true, failing(attempts))) {
      log.write(event(1, "tx-1", "step_succeeded"));
      assertThat(log.flush())
          .hasValueSatisfying(reason -> assertThat(reason).contains("disk gone"));
      assertThat(log.flush()).isPresent();
      assertThat(attempts).hasValue(1);
      assertThatThrownBy(() -> log.write(event(2, "tx-1", "step_succeeded")))
          .isInstanceOf(AuditException.class)
          .hasMessageContaining("refuses writes");
    }
  }

  @Test
  void aFailedTimerSyncIsReportedByTheNextFlush() {
    AtomicInteger attempts = new AtomicInteger();
    try (AuditLog log = AuditLog.open(path(), true, failing(attempts))) {
      log.write(event(1, "tx-1", "step_succeeded"));
      await().atMost(FIVE_SECONDS).until(() -> attempts.get() == 1);
      assertThat(log.flush())
          .hasValueSatisfying(reason -> assertThat(reason).contains("disk gone"));
      assertThat(attempts).hasValue(1);
    }
  }

  @Test
  void aLogThatIsClosedRefusesWritesAndClosingAgainIsHarmless() {
    AuditLog log = open();
    log.close();
    assertThatThrownBy(() -> log.write(event(1, "tx-1", "step_succeeded")))
        .isInstanceOf(AuditException.class);
    assertThatCode(log::close).doesNotThrowAnyException();
  }

  @Test
  void closingFlushesAndReleasesTheLockForTheNextWriter() {
    AuditLog log = open();
    fill(log, 2);
    log.close();
    assertThat(dir.resolve("audit.log.lock")).doesNotExist();
    try (AuditLog next = open()) {
      fill(next, 1);
    }
    assertThat(AuditLog.verify(path()))
        .isInstanceOfSatisfying(Intact.class, i -> assertThat(i.head().records()).isEqualTo(3));
  }

  @Test
  void aLocationThatCannotHoldALogIsRefused() throws IOException {
    Path blocker = dir.resolve("blocker");
    Files.writeString(blocker, "not a directory");
    assertThatThrownBy(() -> AuditLog.open(blocker.resolve("audit.log"), false))
        .isInstanceOf(AuditException.class);
  }
}

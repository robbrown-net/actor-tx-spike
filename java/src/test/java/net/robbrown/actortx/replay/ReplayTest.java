package net.robbrown.actortx.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.robbrown.actortx.audit.AuditLog;
import net.robbrown.actortx.events.Event;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Loading a day from its log: the chain is verified first, and every refusal says why. */
class ReplayTest {

  @TempDir Path dir;

  private Path writeLog(String name, String... txs) {
    Path path = dir.resolve(name);
    try (AuditLog log = AuditLog.open(path, false)) {
      for (int i = 0; i < txs.length; i++) {
        log.write(
            new Event(
                i + 1L,
                1_000 + i,
                txs[i],
                "payment",
                "tx_received",
                "received",
                Map.of("request", Map.of("amount", 100))));
      }
    }
    return path;
  }

  static ReplayException.Reason refusal(ThrowingCallable call) {
    return catchThrowableOfType(call, ReplayException.class).reason();
  }

  @Test
  void aVerifiedLogLoadsWithItsHeadAndItsTransactions() {
    Path path = writeLog("day.log", "a", "b");
    Day day = Replay.load(path);
    assertThat(day.records()).hasSize(2);
    assertThat(day.head()).isPresent();
    assertThat(day.head().orElseThrow().records()).isEqualTo(2L);
    assertThat(day.txOrder()).containsExactly("a", "b");
    assertThat(day.tx("a")).isPresent();
  }

  @Test
  void aLogCanBeLoadedWithoutVerifyingItWhichLeavesNoHead() {
    Day day = Replay.load(writeLog("day.log", "a"), false);
    assertThat(day.head()).isEmpty();
    assertThat(day.transactions()).hasSize(1);
  }

  @Test
  void aMissingLogIsRefused() {
    assertThat(refusal(() -> Replay.load(dir.resolve("none.log"))))
        .isEqualTo(ReplayException.Reason.NO_SUCH_LOG);
    assertThat(refusal(() -> Replay.load(dir.resolve("none.log"), false)))
        .isEqualTo(ReplayException.Reason.NO_SUCH_LOG);
  }

  @Test
  void aTamperedLogIsRefusedUnlessVerificationIsSkipped() throws IOException {
    Path path = writeLog("day.log", "a");
    Files.writeString(path, Files.readString(path).replace("received", "rejected"));
    assertThat(refusal(() -> Replay.load(path))).isEqualTo(ReplayException.Reason.INTEGRITY);
    assertThat(Replay.load(path, false).records()).hasSize(1);
  }

  @Test
  void aLogThatIsNotJsonIsRefusedWhicheverWayItIsLoaded() throws IOException {
    Path path = dir.resolve("bad.log");
    Files.writeString(path, "not json\n");
    assertThat(refusal(() -> Replay.load(path))).isEqualTo(ReplayException.Reason.INTEGRITY);
    assertThat(refusal(() -> Replay.load(path, false)))
        .isEqualTo(ReplayException.Reason.MALFORMED_LOG);
  }

  @Test
  void aDirectoryIsNotALogAndIsUnreadable() {
    assertThat(refusal(() -> Replay.load(dir))).isEqualTo(ReplayException.Reason.UNREADABLE);
    assertThat(refusal(() -> Replay.load(dir, false))).isEqualTo(ReplayException.Reason.UNREADABLE);
  }

  @Test
  void everyRegionalLogInADirectoryIsLoadedByName() throws IOException {
    writeLog("IN.log", "a");
    writeLog("US.log", "b", "c");
    Files.writeString(dir.resolve("notes.txt"), "not a log");
    Map<String, Day> days = Replay.loadDir(dir);
    assertThat(days.keySet()).containsExactly("IN", "US");
    assertThat(days.get("US").txOrder()).containsExactly("b", "c");
    assertThat(Replay.loadDir(dir, false)).hasSize(2);
  }

  @Test
  void aMissingDirectoryHasNoLogs() {
    assertThat(Replay.loadDir(dir.resolve("absent"))).isEmpty();
  }

  @Test
  void aTamperedRegionRefusesTheWholeDirectory() throws IOException {
    writeLog("IN.log", "a");
    Path us = writeLog("US.log", "b");
    Files.writeString(us, Files.readString(us).replace("received", "rejected"));
    assertThat(refusal(() -> Replay.loadDir(dir))).isEqualTo(ReplayException.Reason.INTEGRITY);
    assertThat(Replay.loadDir(dir, false)).hasSize(2);
  }

  @Test
  void aDayCanBeBuiltFromRecordsAndNamesItsTerminalKinds() {
    Day day =
        Replay.fromRecords(
            List.of(Map.of("n", 1L, "at", 1L, "tx", "t", "kind", "tx_received", "data", Map.of())));
    assertThat(day.txOrder()).containsExactly("t");
    assertThat(day.head()).isEmpty();
    assertThat(Replay.terminalKinds()).contains("tx_failed");
  }

  @Test
  void aReplayRefusesAnUnreadablePathWithItsCause() {
    ReplayException thrown = catchThrowableOfType(() -> Replay.load(dir), ReplayException.class);
    assertThat(thrown.getMessage()).contains(dir.toString());
  }
}

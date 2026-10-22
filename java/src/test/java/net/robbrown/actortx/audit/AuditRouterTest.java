package net.robbrown.actortx.audit;

import static net.robbrown.actortx.audit.AuditFixtures.decision;
import static net.robbrown.actortx.audit.AuditFixtures.event;
import static net.robbrown.actortx.audit.AuditFixtures.failing;
import static net.robbrown.actortx.audit.AuditFixtures.received;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import net.robbrown.actortx.events.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditRouterTest {

  @TempDir Path dir;

  private AuditRouter open(List<String> logs) {
    return AuditRouter.open(dir, logs, false);
  }

  private static void writeAll(AuditRouter router, Event... events) {
    for (Event event : events) {
      router.write(event);
    }
  }

  private List<Map<String, Object>> records(String log) {
    return AuditChain.records(dir.resolve(log + ".log"));
  }

  private List<String> txIds(String log) {
    return records(log).stream()
        .map(r -> (String) r.get("tx"))
        .filter(Objects::nonNull)
        .distinct()
        .toList();
  }

  private List<String> kindsFor(String log, String tx) {
    return records(log).stream()
        .filter(r -> tx.equals(r.get("tx")))
        .map(r -> String.valueOf(r.get("kind")))
        .toList();
  }

  @Test
  void eachTransactionsEventsGoOnlyToTheLogsItsDecisionNames() {
    try (AuditRouter router = open(List.of("IN", "US"))) {
      writeAll(
          router,
          received(1, "t-in", "IN"),
          event(2, "t-in", "step_started"),
          received(3, "t-glob", "GLOBAL"),
          received(4, "t-multi", "GLOBAL", "US"),
          event(5, "t-in", "tx_completed"),
          event(6, null, "health"),
          event(7, "t-in", "tx_costed"));
      assertThat(router.flush()).isEmpty();
      assertThat(txIds("IN")).containsExactly("t-in");
      assertThat(kindsFor("IN", "t-in"))
          .containsExactly("tx_received", "step_started", "tx_completed", "tx_costed");
      assertThat(txIds("US")).containsExactly("t-multi");
      assertThat(txIds("GLOBAL")).containsExactlyInAnyOrder("t-glob", "t-multi");
      assertThat(records("GLOBAL")).anyMatch(r -> "health".equals(r.get("kind")));
      assertThat(router.table().logsByTx())
          .doesNotContainKey("t-in")
          .containsEntry("t-multi", List.of("GLOBAL", "US"));
    }
  }

  @Test
  void aTransactionTheRouterNeverSawStartGoesToTheGlobalLog() {
    try (AuditRouter router = open(List.of("IN"))) {
      router.write(event(1, "stray", "step_started"));
    }
    assertThat(txIds("GLOBAL")).containsExactly("stray");
  }

  @Test
  void aResumedTransactionReTeachesTheRouterWhereItBelongs() {
    try (AuditRouter router = open(List.of("US"))) {
      writeAll(
          router,
          event(1, "back", "tx_resumed", "m", decision("US")),
          event(2, "back", "step_started"));
    }
    assertThat(kindsFor("US", "back")).containsExactly("tx_resumed", "step_started");
  }

  @Test
  void aRestartedRouterRemembersOpenTransactionsAndForgetsFinishedOnes() {
    try (AuditRouter first = open(List.of("IN", "US"))) {
      writeAll(
          first,
          received(1, "open-in", "IN"),
          received(2, "done-in", "IN"),
          event(3, "done-in", "tx_costed"),
          received(4, "open-resumed", "US"),
          event(6, "open-in", "step_started"));
    }
    try (AuditRouter second = open(List.of("IN", "US"))) {
      assertThat(second.table().logsByTx())
          .isEqualTo(Map.of("open-in", List.of("IN"), "open-resumed", List.of("US")));
      second.write(event(5, "open-in", "step_started"));
    }
    assertThat(kindsFor("IN", "open-in"))
        .containsExactly("tx_received", "step_started", "step_started");
    assertThat(txIds("GLOBAL")).doesNotContain("open-in");
  }

  @Test
  void regionalLogsAreSeparateChainsWithTheirOwnHeadsAndPaths() {
    try (AuditRouter router = open(List.of("IN", "US"))) {
      writeAll(router, received(1, "a", "IN"), received(2, "b", "US"));
      assertThat(router.flush()).isEmpty();
      Map<String, AuditLog.Head> heads = router.heads();
      assertThat(heads.get("IN").records()).isEqualTo(1);
      assertThat(heads.get("US").records()).isEqualTo(1);
      assertThat(heads.get("GLOBAL").records()).isZero();
      assertThat(heads.get("IN").hash()).isNotEqualTo(heads.get("US").hash());
      assertThat(router.paths()).containsOnlyKeys("GLOBAL", "IN", "US");
      router
          .paths()
          .values()
          .forEach(
              p -> assertThat(AuditLog.verify(p)).isInstanceOf(AuditLog.Verification.Intact.class));
    }
  }

  @Test
  void residencyIsVisibleInTheLogsAndAMultiRegionTransactionIsInEachRegion() {
    try (AuditRouter router = open(List.of("IN", "US"))) {
      writeAll(
          router,
          received(1, "a", "IN"),
          event(2, "a", "tx_completed"),
          received(3, "b", "GLOBAL", "US"));
    }
    assertThat(txIds("IN")).containsExactly("a");
    assertThat(txIds("US")).containsExactly("b");
    assertThat(txIds("GLOBAL")).containsExactly("b").doesNotContain("a");
  }

  @Test
  void aDecisionNamingAnUnknownLogIsRefusedAndNothingIsWritten() {
    try (AuditRouter router = open(List.of("IN"))) {
      assertThatThrownBy(() -> router.write(received(1, "lost", "BR")))
          .isInstanceOf(AuditException.class)
          .hasMessageContaining("no audit log named \"BR\"");
      assertThat(txIds("GLOBAL")).doesNotContain("lost");
      assertThat(txIds("IN")).doesNotContain("lost");
    }
  }

  @Test
  void aRegionalLogTamperedWhileStoppedRefusesTheRouter() throws IOException {
    try (AuditRouter router = open(List.of("IN"))) {
      router.write(received(1, "a", "IN"));
    }
    Path in = dir.resolve("IN.log");
    Files.writeString(in, Files.readString(in).replace("\"message\":\"m\"", "\"message\":\"x\""));
    assertThatThrownBy(() -> open(List.of("IN")))
        .isInstanceOf(AuditException.class)
        .hasMessageContaining("fails verification");
  }

  @Test
  void aLogThatCannotBeOpenedRefusesTheRouterAndReleasesTheOthers() throws IOException {
    Files.createDirectories(dir.resolve("US.log"));
    assertThatThrownBy(() -> open(List.of("IN", "US"))).isInstanceOf(AuditException.class);
    assertThat(dir.resolve("IN.log.lock")).doesNotExist();
    assertThat(dir.resolve("US.log.lock")).doesNotExist();
  }

  @Test
  void aSecondRouterOverTheSameDirectoryIsRefused() {
    try (AuditRouter first = open(List.of("IN"))) {
      assertThatThrownBy(() -> open(List.of("IN")))
          .isInstanceOf(AuditException.class)
          .hasMessageContaining("held by writer");
      assertThat(first.paths()).containsKey("IN");
    }
  }

  @Test
  void aRouterWithNoRegionsHasOnlyTheGlobalLogAndNamesAreNotDuplicated() {
    try (AuditRouter bare = open(List.of())) {
      assertThat(bare.paths()).containsOnlyKeys("GLOBAL");
    }
    try (AuditRouter dup = open(List.of("IN", "IN", "GLOBAL"))) {
      assertThat(dup.paths()).containsOnlyKeys("GLOBAL", "IN");
    }
  }

  @Test
  void theDirectoryIsCreatedWhenMissingAndRefusedWhenItIsAFile() throws IOException {
    Path nested = dir.resolve("nested/regions");
    try (AuditRouter router = AuditRouter.open(nested, List.of("IN"), false)) {
      assertThat(router.paths().get("IN")).isEqualTo(nested.resolve("IN.log"));
    }
    Path blocker = dir.resolve("blocker");
    Files.writeString(blocker, "not a directory");
    assertThatThrownBy(() -> AuditRouter.open(blocker, List.of("IN"), false))
        .isInstanceOf(AuditException.class);
  }

  @Test
  void aFlushReportsTheFirstFailureAndStillFlushesEveryDirtyLog() {
    AtomicInteger attempts = new AtomicInteger();
    try (AuditRouter router = AuditRouter.open(dir, List.of("IN", "US"), true, failing(attempts))) {
      writeAll(router, received(1, "t", "IN"), event(2, null, "health"));
      assertThat(router.flush())
          .hasValueSatisfying(reason -> assertThat(reason).contains("disk gone"));
      assertThat(attempts).hasValue(2);
    }
  }

  @Test
  void aRouterIsADurableSinkAndReleasesEveryLockWhenClosed() {
    AuditRouter router = AuditRouter.open(dir, List.of("IN"), true);
    router.write(received(1, "t", "IN"));
    assertThat(router.flush()).isEmpty();
    router.close();
    assertThat(dir.resolve("IN.log.lock")).doesNotExist();
    assertThat(dir.resolve("GLOBAL.log.lock")).doesNotExist();
    assertThatThrownBy(() -> router.write(event(2, "t", "step_started")))
        .isInstanceOf(AuditException.class);
  }

  @Test
  void aTransactionInReviewKeepsItsRoutingUntilTheReviewIsResolved() {
    try (AuditRouter router = open(List.of("IN"))) {
      writeAll(
          router,
          received(1, "rv-in", "IN"),
          event(2, "rv-in", "needs_review"),
          event(3, "rv-in", "tx_costed"),
          event(4, "rv-in", "review_checked"),
          received(5, "plain", "IN"),
          event(6, "plain", "tx_costed"));
      assertThat(router.flush()).isEmpty();
      assertThat(router.table().logsByTx()).isEqualTo(Map.of("rv-in", List.of("IN")));
      assertThat(router.table().reviewing()).containsExactly("rv-in");
      assertThat(kindsFor("IN", "rv-in"))
          .containsExactly("tx_received", "needs_review", "tx_costed", "review_checked");
      assertThat(txIds("GLOBAL")).doesNotContain("rv-in");

      router.write(event(7, "rv-in", "review_resolved"));
      assertThat(router.table().logsByTx()).isEmpty();
      assertThat(router.table().reviewing()).isEmpty();
      assertThat(kindsFor("IN", "rv-in")).endsWith("review_resolved");
    }
  }

  @Test
  void aRestartedRouterRebuildsWhatIsStillInReviewFromTheLogs() {
    try (AuditRouter first = open(List.of("IN", "US"))) {
      writeAll(
          first,
          received(1, "rv-open", "IN"),
          event(2, "rv-open", "needs_review"),
          event(3, "rv-open", "tx_costed"),
          received(4, "rv-done", "US"),
          event(5, "rv-done", "needs_review"),
          event(6, "rv-done", "tx_costed"),
          event(7, "rv-done", "review_resolved"));
    }
    try (AuditRouter second = open(List.of("IN", "US"))) {
      assertThat(second.table().logsByTx()).isEqualTo(Map.of("rv-open", List.of("IN")));
      assertThat(second.table().reviewing()).containsExactly("rv-open");
    }
  }
}

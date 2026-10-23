package net.robbrown.actortx.replay;

import static net.robbrown.actortx.replay.ReplayFixtures.edit;
import static net.robbrown.actortx.replay.ReplayFixtures.goodDay;
import static net.robbrown.actortx.replay.ReplayFixtures.kind;
import static net.robbrown.actortx.replay.ReplayFixtures.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import net.robbrown.actortx.audit.AuditRouter;
import net.robbrown.actortx.events.Event;
import net.robbrown.actortx.payment.Outcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Replay against real audit logs: a narrative is written through the regional router (real fsyncs,
 * real residency), then loaded back, verified, judged and reported on.
 */
class ReplayIT {

  private static final List<String> REGIONS = List.of("IN", "US");

  @TempDir Path dir;

  /** Route the happy transaction to IN and the failed one to US, as their decisions would. */
  private static List<Map<String, Object>> narrative() {
    List<Map<String, Object>> happy = ofTx(goodDay(), "happy");
    List<Map<String, Object>> failed = ofTx(goodDay(), "failed");
    List<Map<String, Object>> health = goodDay().stream().filter(r -> r.get("tx") == null).toList();
    List<Map<String, Object>> inRegion =
        edit(
            happy,
            kind("tx_received", "happy"),
            r -> with(r, List.of("IN"), "data", "eligibility", "logs"));
    List<Map<String, Object>> usRegion =
        edit(
            failed,
            kind("tx_received", "failed"),
            r -> with(r, List.of("US"), "data", "eligibility", "logs"));
    return Stream.of(inRegion, usRegion, health).flatMap(List::stream).toList();
  }

  private static List<Map<String, Object>> ofTx(List<Map<String, Object>> records, String tx) {
    return records.stream().filter(r -> tx.equals(r.get("tx"))).toList();
  }

  private static Event event(Map<String, Object> record) {
    return new Event(
        (Long) record.get("seq"),
        (Long) record.get("at"),
        (String) record.get("tx"),
        (String) record.get("actor"),
        (String) record.get("kind"),
        (String) record.get("message"),
        Fields.data(record));
  }

  private void writeNarrative() {
    try (AuditRouter router = AuditRouter.open(dir, REGIONS, true)) {
      for (Map<String, Object> record : narrative()) {
        router.write(event(record));
      }
      assertThat(router.flush()).isEmpty();
    }
  }

  @Test
  void aNarrativeWrittenThroughTheRouterReplaysAndHoldsEveryRule() {
    writeNarrative();

    Map<String, Day> days = Replay.loadDir(dir);
    assertThat(days.keySet()).containsExactly("GLOBAL", "IN", "US");
    assertThat(days.get("IN").txOrder()).containsExactly("happy");
    assertThat(days.get("US").txOrder()).containsExactly("failed");

    Rules.Verdict verdict = Rules.verify(days.get("IN"));
    assertThat(verdict.violations()).isEmpty();
    assertThat(Report.verify(verdict)).contains("ALL RULES HELD");
    assertThat(Residency.check(days).ok()).isTrue();
    assertThat(Residency.check(days).transactions()).isEqualTo(2);
  }

  @Test
  void theStateOfTheLiveDayAndTheWhatIfsAreReadFromTheLogs() {
    writeNarrative();
    Day in = Replay.loadDir(dir).get("IN");

    State end = Replay.stateAt(in, Position.seq(Long.MAX_VALUE));
    assertThat(end.outcomes()).containsEntry(Optional.of(Outcome.COMPLETED), 1L);
    assertThat(end.inFlight()).isEmpty();
    assertThat(Report.state(end)).contains("transactions: 1 completed");

    assertThat(WhatIf.run(in, Map.of("max_bps_from_best", 0.0)))
        .isInstanceOf(net.robbrown.actortx.validation.Validated.Valid.class);
    assertThat(Compare.diff(in, in).identical()).isEqualTo(1);
  }

  @Test
  void aLogTamperedAfterTheFactIsRefusedWhenItIsLoaded() throws IOException {
    writeNarrative();
    Path in = dir.resolve("IN.log");
    Files.writeString(in, Files.readString(in).replace("tx_completed", "tx_failed"));

    ReplayException refused =
        catchThrowableOfType(() -> Replay.loadDir(dir), ReplayException.class);
    assertThat(refused.reason()).isEqualTo(ReplayException.Reason.INTEGRITY);
    assertThat(refused.getMessage()).contains("IN.log");
  }
}

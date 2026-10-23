package net.robbrown.actortx.replay;

import static net.robbrown.actortx.replay.ReplayFixtures.goodDay;
import static net.robbrown.actortx.replay.ReplayFixtures.m;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.robbrown.actortx.payment.Outcome;
import org.junit.jupiter.api.Test;

/** The system as it stood at a moment, folded from the records up to it. */
class StateTest {

  private static final Day DAY = Replay.fromRecords(goodDay());

  private static Map<String, Object> recordOf(String kind, String tx) {
    return DAY.records().stream()
        .filter(r -> kind.equals(r.get("kind")) && tx.equals(r.get("tx")))
        .findFirst()
        .orElseThrow();
  }

  /** A record with the fields the state reads, numbered and stamped by hand. */
  private static Map<String, Object> rec(long n, String kind, String tx, Map<String, Object> data) {
    return m(
        "n", n, "seq", n, "at", n * 10, "tx", tx, "actor", "operator", "kind", kind, "data", data);
  }

  @Test
  void theStateAtASelectionShowsTheConditionsThen() {
    Map<String, Object> selection = recordOf("selection_made", "happy");
    State early = Replay.stateAt(DAY, Position.n((Long) selection.get("n")));
    assertThat(early.inFlight()).containsExactly("happy");
    assertThat(early.outcomes()).isEqualTo(Map.of(Optional.empty(), 1L));
    assertThat(early.policy()).containsEntry("max_bps_from_best", 1_000.0);
    assertThat(early.guarantee()).containsEntry("rate", 105_000_000L);
    assertThat(early.config()).containsKey("max_attempts");
    assertThat(early.providers().get("best").lastQuote()).containsEntry("outcome", "quote");
    assertThat(early.economics().transactions()).isZero();
    assertThat(early.controls()).isEmpty();
    assertThat(early.asOf().lastN()).isEqualTo(selection.get("n"));
  }

  @Test
  void theStateAtAMomentAndAfterTheEndOfTheDay() {
    Map<String, Object> completed = recordOf("tx_completed", "happy");
    State mid = Replay.stateAt(DAY, Position.atMs((Long) completed.get("at")));
    assertThat(mid.outcomes()).containsEntry(Optional.of(Outcome.COMPLETED), 1L);
    assertThat(mid.asOf().lastAtMs()).isLessThanOrEqualTo((Long) completed.get("at"));

    State done = Replay.stateAt(DAY, Position.seq((Long) completed.get("seq") + 10_000));
    assertThat(done.inFlight()).isEmpty();
    assertThat(done.outcomes())
        .isEqualTo(
            Map.of(
                Optional.of(Outcome.COMPLETED),
                3L,
                Optional.of(Outcome.FAILED),
                1L,
                Optional.of(Outcome.NEEDS_REVIEW),
                1L));
    assertThat(done.economics().transactions()).isEqualTo(1L);
    assertThat(done.economics().savedVsMedian()).isEqualTo(2_000L);
    assertThat(done.economics().costPicos()).isEqualTo(1_200L);
    assertThat(done.economics().margin()).isEqualTo(5_000L);
  }

  @Test
  void theStateBeforeAnythingHappenedIsEmpty() {
    State nothing = Replay.stateAt(DAY, Position.n(0));
    assertThat(nothing.transactions()).isEmpty();
    assertThat(nothing.policy()).isEmpty();
    assertThat(nothing.asOf().lastN()).isNull();
    assertThat(nothing.asOf().lastAtMs()).isNull();
  }

  @Test
  void operatorActionsAndHealthAreRecordedAsTheyHappened() {
    Day day = Replay.fromRecords(controlAndHealth());
    State s = Replay.stateAt(day, Position.atMs(25));
    assertThat(s.controls()).hasSize(1);
    assertThat(s.controls().get(0).command()).containsEntry("action", "pace");
    assertThat(s.providers().get("p").health()).containsEntry("state", "stalled");
    assertThat(s.providers().get("p").lastQuote()).isEmpty();
    assertThat(Report.state(s)).contains("p: stalled, breaker");
    assertThat(Replay.stateAt(day, Position.atMs(35)).providers().get("p").health())
        .containsEntry("state", "healthy");
  }

  private static List<Map<String, Object>> controlAndHealth() {
    return List.of(
        rec(1, "control", null, m("command", m("action", "pace"))),
        rec(2, "health", null, health("stalled", "open")),
        rec(3, "health", null, health("healthy", "closed")));
  }

  private static Map<String, Object> health(String state, String breaker) {
    return m(
        "provider",
        "p",
        "state",
        state,
        "breaker",
        breaker,
        "error_rate",
        0.0,
        "p95_ms",
        9,
        "version",
        "1",
        "recovering",
        0);
  }

  @Test
  void aProviderSeenOnlyThroughAQuoteHasNoHealthRecord() {
    State s = Replay.stateAt(DAY, Position.n(3));
    assertThat(s.providers().get("best").health()).isEmpty();
    assertThat(Report.state(s)).contains("best: no health record");
  }

  @Test
  void aCostedTransactionWithoutItsValueOrCostIsNotCounted() {
    List<Map<String, Object>> records =
        List.of(
            rec(1, "tx_costed", "x", m("value", m("margin", 3))),
            rec(2, "tx_costed", "x", m("value", m("margin", 4), "cost", m("total", 6))));
    State s = Replay.stateAt(Replay.fromRecords(records), Position.n(9));
    assertThat(s.economics().transactions()).isEqualTo(1L);
    assertThat(s.economics().margin()).isEqualTo(4L);
    assertThat(s.economics().costPicos()).isEqualTo(6L);
  }
}

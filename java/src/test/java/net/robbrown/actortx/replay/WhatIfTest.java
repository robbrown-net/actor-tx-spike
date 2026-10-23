package net.robbrown.actortx.replay;

import static net.robbrown.actortx.replay.ReplayFixtures.goodDay;
import static net.robbrown.actortx.replay.ReplayFixtures.m;
import static net.robbrown.actortx.replay.ReplayFixtures.quote;
import static net.robbrown.actortx.replay.ReplayFixtures.received;
import static net.robbrown.actortx.replay.ReplayFixtures.selectStart;
import static net.robbrown.actortx.replay.ReplayFixtures.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import java.util.Map;
import net.robbrown.actortx.replay.ReplayFixtures.Log;
import net.robbrown.actortx.validation.Validated;
import org.junit.jupiter.api.Test;

/** What a different policy would have done on a real day, re-judged from the recorded answers. */
class WhatIfTest {

  private static WhatIf.Result run(Day day, Map<String, Object> overrides) {
    return switch (WhatIf.run(day, overrides)) {
      case Validated.Valid<WhatIf.Result> valid -> valid.value();
      case Validated.Invalid<WhatIf.Result> invalid -> throw new AssertionError(invalid.errors());
    };
  }

  /**
   * Two providers: the live policy (cost) picks the slow one; a speed policy would pick the fast
   * one.
   */
  private static Day slowBest() {
    Log log = new Log();
    log.add("tx_received", "slow", received(100_000, "a", "b"));
    selectStart(log, "slow");
    quote(log, "slow", "a", 110_000_000L, 500);
    quote(log, "slow", "b", 105_000_000L, 10);
    log.add("selection_made", "slow", m("ranking", List.of(m("provider", "a", "net", 110_000L))));
    log.add("provider_chosen", "slow", m("provider", "a"));
    return Replay.fromRecords(log.records());
  }

  @Test
  void aChangedPolicyWithTheSameInputsChangesNothing() {
    WhatIf.Result same = run(Replay.fromRecords(goodDay()), Map.of());
    assertThat(same.changed()).isZero();
    assertThat(same.netDelta()).isZero();
    assertThat(same.transactions()).isEqualTo(5);
    assertThat(same.comparable()).isEqualTo(5);
    assertThat(same.rows()).allMatch(row -> !row.changed());
  }

  @Test
  void aSpeedPolicyRoutesTheSameDayDifferentlyAndSaysWhatItIsWorth() {
    WhatIf.Result speed = run(slowBest(), Map.of("weights", Map.of("speed", 1)));
    assertThat(speed.comparable()).isEqualTo(1);
    assertThat(speed.changed()).isEqualTo(1);
    WhatIf.Row row = speed.rows().get(0);
    assertThat(row.base()).isEqualTo("a");
    assertThat(row.alt()).isEqualTo("b");
    assertThat(row.changed()).isTrue();
    assertThat(speed.netDelta()).isEqualTo(-5_000L);
    assertThat(speed.latencyDeltaMs()).isEqualTo(-490L);
    assertThat(Report.whatIf(speed)).contains("1 of 1 transactions would route differently");
  }

  @Test
  void aCheapestPolicyStillJudgesEveryTransactionAndAStrictOneKeepsThem() {
    Day day = Replay.fromRecords(goodDay());
    assertThat(run(day, Map.of("weights", Map.of("cost", 1))).netDelta()).isZero();
    assertThat(run(day, Map.of("max_bps_from_best", 0.0)).transactions()).isEqualTo(5);
  }

  @Test
  void aPolicyThatLeavesNothingWithinItsRulesCountsTheTransactionsWithNoOption() {
    WhatIf.Result none = run(Replay.fromRecords(goodDay()), Map.of("min_validity_ms", 10_000_000));
    assertThat(none.lostAllOptions()).isEqualTo(5);
    assertThat(none.rows()).allMatch(row -> row.alt() == null);
    assertThat(none.comparable()).isZero();
    assertThat(Report.whatIf(none)).contains("would have had no option at all");
  }

  @Test
  void anOverrideThatIsNotAValidPolicyIsRefusedUpFront() {
    Validated<WhatIf.Result> refused =
        WhatIf.run(Replay.fromRecords(goodDay()), Map.of("max_bps_from_best", -1));
    assertThat(refused).isInstanceOf(Validated.Invalid.class);
    assertThat(((Validated.Invalid<WhatIf.Result>) refused).errors())
        .anyMatch(e -> e.contains("max_bps_from_best"));
  }

  @Test
  void aDayWithoutRecordedQuotesHasNothingToReJudge() {
    Day day =
        Replay.fromRecords(
            List.of(
                m(
                    "n",
                    1L,
                    "at",
                    1L,
                    "tx",
                    "t",
                    "kind",
                    "tx_received",
                    "data",
                    m("request", m("amount", 1), "policy", m()))));
    WhatIf.Result result = run(day, Map.of("max_bps_from_best", 5.0));
    assertThat(result.transactions()).isZero();
    assertThat(result.rows()).isEmpty();
  }

  @Test
  void aRecordedPolicyThatCannotBeChangedIsAReplayRefusal() {
    Log log = new Log();
    log.add("tx_received", "bad", with(received(100_000, "a"), 0, "policy", "max_latency_ms"));
    quote(log, "bad", "a", 110_000_000L, 20);
    Day day = Replay.fromRecords(log.records());
    ReplayException thrown =
        catchThrowableOfType(
            () -> WhatIf.run(day, Map.of("min_validity_ms", 0)), ReplayException.class);
    assertThat(thrown.reason()).isEqualTo(ReplayException.Reason.INVALID_POLICY);
  }
}

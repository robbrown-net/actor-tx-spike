package net.robbrown.actortx.replay;

import static net.robbrown.actortx.replay.ReplayFixtures.drop;
import static net.robbrown.actortx.replay.ReplayFixtures.edit;
import static net.robbrown.actortx.replay.ReplayFixtures.find;
import static net.robbrown.actortx.replay.ReplayFixtures.goodDay;
import static net.robbrown.actortx.replay.ReplayFixtures.kind;
import static net.robbrown.actortx.replay.ReplayFixtures.with;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import net.robbrown.actortx.replay.WhatIf.Result;
import net.robbrown.actortx.validation.Validated;
import org.junit.jupiter.api.Test;

/** The plain-text renderings: verdicts, states, what-ifs, diffs and batches. */
class ReportTest {

  private static Day good() {
    return Replay.fromRecords(goodDay());
  }

  @Test
  void aVerdictSaysWhatHeldOrWhatBroke() {
    assertThat(Report.verify(Rules.verify(good())))
        .contains(" transactions (3 completed, 1 failed, 1 needs_review): ALL RULES HELD");

    Map<String, Object> pay =
        find(
            goodDay(),
            r -> "step_succeeded".equals(r.get("kind")) && "pay".equals(Fields.data(r).get("op")));
    List<Map<String, Object>> broken = new java.util.ArrayList<>(goodDay());
    broken.add(pay);
    String text = Report.verify(Rules.verify(Replay.fromRecords(broken)));
    assertThat(text).contains("VIOLATION").contains("[single_pay]");
  }

  @Test
  void aViolationWithNoTransactionOrRecordNumberStillReads() {
    Map<String, Object> record = Map.of("n", 2L, "at", 1L, "kind", "x", "data", Map.of());
    String text =
        Report.verify(Rules.verify(Replay.fromRecords(List.of(record, Map.of("n", 1L, "at", 0L)))));
    assertThat(text).contains("[time_ordered] - record 1");
  }

  @Test
  void aStateSaysWhatIsInFlightAndWhatEachProviderLooksLike() {
    State s = Replay.stateAt(good(), Position.seq(1_000_000));
    String text = Report.state(s);
    assertThat(text)
        .contains("transactions: 3 completed, 1 failed, 1 needs_review")
        .contains("in flight: []")
        .contains(
            "economics so far: saved 20.00, margin 50.00, cost 1200 pico-units over 1 transactions")
        .contains("policy in force:")
        .contains("providers:")
        .contains("  best: healthy, breaker closed, last quote");
  }

  @Test
  void aWhatIfSaysWhatWouldHaveChanged() {
    Result what =
        ((Validated.Valid<Result>) WhatIf.run(good(), Map.of("min_validity_ms", 10_000_000)))
            .value();
    String text = Report.whatIf(what);
    assertThat(text).startsWith("what if %{\"min_validity_ms\" => 10000000}: 0 of 0 transactions");
    assertThat(text).contains("5 would have had no option at all");
  }

  @Test
  void aChangedRouteIsListedWithItsNetEffect() {
    List<Map<String, Object>> records =
        edit(goodDay(), kind("tx_economics", "happy"), r -> with(r, "second", "data", "provider"));
    Result what =
        ((Validated.Valid<Result>)
                WhatIf.run(Replay.fromRecords(records), Map.of("weights", Map.of("speed", 1))))
            .value();
    String text = Report.whatIf(what);
    assertThat(text).contains("what if %{\"weights\" => %{\"speed\" => 1}}:");
  }

  @Test
  void aDiffSaysHowManyAgreeAndWhereTheyDiffer() {
    Day other = Replay.fromRecords(drop(goodDay(), r -> "mixed".equals(r.get("tx"))));
    String text = Report.diff(Compare.diff(good(), other));
    assertThat(text)
        .startsWith(
            "4 transactions in both, 4 identical, 0 divergence(s); only in A: 1, only in B: 0");
  }

  @Test
  void aDivergenceShowsBothValuesAsTheyWereRecorded() {
    Day other =
        Replay.fromRecords(
            drop(
                goodDay(),
                r -> "tx_completed".equals(r.get("kind")) && "mixed".equals(r.get("tx"))));
    String text = Report.diff(Compare.diff(good(), other));
    assertThat(text).contains("  mixed outcome: A=:completed B=:open");
  }

  @Test
  void aProviderWithNoHealthSaysSo() {
    State s = Replay.stateAt(good(), Position.n(3));
    assertThat(Report.state(s)).contains("  best: no health record, breaker ?, last quote");
  }

  @Test
  void batchesReadAsLines() {
    assertThat(Report.batches(List.of())).isEqualTo("no bulk batches recorded");
  }
}

package net.robbrown.actortx.replay;

import static net.robbrown.actortx.replay.ReplayFixtures.drop;
import static net.robbrown.actortx.replay.ReplayFixtures.edit;
import static net.robbrown.actortx.replay.ReplayFixtures.find;
import static net.robbrown.actortx.replay.ReplayFixtures.goodDay;
import static net.robbrown.actortx.replay.ReplayFixtures.kind;
import static net.robbrown.actortx.replay.ReplayFixtures.m;
import static net.robbrown.actortx.replay.ReplayFixtures.with;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.robbrown.actortx.replay.Rules.Violation;
import org.junit.jupiter.api.Test;

/**
 * The rules against a recorded day: the good day holds every rule, and each rule catches the
 * violation it exists for (breaking a good day in exactly one way).
 */
class RulesTest {

  private static List<Rule> rules(List<Map<String, Object>> records) {
    return violations(records, null).stream().map(Violation::rule).distinct().toList();
  }

  private static List<Violation> violations(List<Map<String, Object>> records, Rule only) {
    return Rules.verify(Replay.fromRecords(records)).violations().stream()
        .filter(v -> only == null || v.rule() == only)
        .toList();
  }

  @Test
  void aRecordedDayHoldsEveryRuleAndIsReadBackFully() {
    Day day = Replay.fromRecords(goodDay());
    Rules.Verdict verdict = Rules.verify(day);
    assertThat(verdict.violations()).isEmpty();
    assertThat(verdict.transactions()).isEqualTo(5);
    assertThat(verdict.outcomes())
        .isEqualTo(Map.of(Outcome.COMPLETED, 3L, Outcome.FAILED, 1L, Outcome.NEEDS_REVIEW, 1L));
    List<TxSummary> txs = day.transactions();
    assertThat(txs.stream().map(TxSummary::id).toList())
        .containsExactly("happy", "failover", "review", "failed", "mixed");
    TxSummary happy = txs.get(0);
    assertThat(happy.outcome()).isEqualTo(Outcome.COMPLETED);
    assertThat(happy.provider()).isEqualTo("best");
    assertThat(happy.steps()).containsExactly("accept_quote", "authorise", "pay", "settle");
    assertThat(happy.net()).isEqualTo(110_000L);
    assertThat(happy.margin()).isEqualTo(5_000L);
    assertThat(txs.get(1).provider()).isEqualTo("second");
    assertThat(txs.get(1).outcome()).isEqualTo(Outcome.COMPLETED);
    assertThat(txs.get(2).outcome()).isEqualTo(Outcome.NEEDS_REVIEW);
    assertThat(txs.get(2).provider()).isEqualTo("best");
    assertThat(txs.get(3).outcome()).isEqualTo(Outcome.FAILED);
    assertThat(txs.get(3).steps()).isEmpty();
    assertThat(txs.get(4).outcome()).isEqualTo(Outcome.COMPLETED);
    assertThat(txs.get(4).provider()).isEqualTo("a");
    assertThat(day.tx("no-such-tx")).isEmpty();
    assertThat(day.tx("happy").orElseThrow().id()).isEqualTo("happy");
    assertThat(Replay.terminalKinds()).containsExactly("tx_completed", "tx_failed", "needs_review");
  }

  @Test
  void singlePayIsCaught() {
    List<Map<String, Object>> records = goodDay();
    Map<String, Object> pay =
        find(
            records,
            r -> "step_succeeded".equals(r.get("kind")) && "pay".equals(Fields.data(r).get("op")));
    assertThat(rules(plus(records, pay))).contains(Rule.SINGLE_PAY);
  }

  @Test
  void completedIsWholeCatchesAMissingStep() {
    List<Map<String, Object>> records =
        drop(
            goodDay(),
            r ->
                "step_succeeded".equals(r.get("kind"))
                    && "authorise".equals(Fields.data(r).get("op"))
                    && "happy".equals(r.get("tx")));
    assertThat(rules(records)).contains(Rule.COMPLETED_IS_WHOLE);
  }

  @Test
  void failedCleanCatchesAnEffectLeftUndone() {
    assertThat(rules(drop(goodDay(), kind("undone", "failover")))).contains(Rule.FAILED_CLEAN);
  }

  @Test
  void noFailoverAfterDoubtCatchesMovingOnAfterPayWasInDoubt() {
    Map<String, Object> doubt = find(goodDay(), kind("in_doubt", "review"));
    Map<String, Object> moved = with(doubt, "failover", "kind");
    assertThat(rules(plus(goodDay(), moved))).contains(Rule.NO_FAILOVER_AFTER_DOUBT);
  }

  @Test
  void idempotencyCatchesAWrongKeyAndAChangingKey() {
    List<Map<String, Object>> wrong =
        edit(goodDay(), kind("step_started", "happy"), r -> with(r, "oops", "data", "key"));
    assertThat(rules(wrong)).contains(Rule.IDEMPOTENCY_KEYS);

    Map<String, Object> pay =
        find(
            goodDay(),
            r -> kind("step_started", "happy").test(r) && "pay".equals(Fields.data(r).get("op")));
    List<Map<String, Object>> varying =
        plus(goodDay(), with(pay, "happy:best:other", "data", "key"));
    assertThat(violations(varying, Rule.IDEMPOTENCY_KEYS)).isNotEmpty();
  }

  @Test
  void selectionReproducibleCatchesARankingTheInputsCannotProduce() {
    List<Map<String, Object>> records =
        edit(
            goodDay(),
            kind("selection_made", "happy"),
            r -> with(r, reversed(Fields.data(r).get("ranking")), "data", "ranking"));
    assertThat(rules(records)).contains(Rule.SELECTION_REPRODUCIBLE);
  }

  @Test
  void quoteValidAtAcceptCatchesAcceptingAnExpiredQuote() {
    List<Map<String, Object>> records =
        edit(
            goodDay(),
            r ->
                kind("quote_observed", "happy").test(r)
                    && "best".equals(Fields.data(r).get("provider")),
            r -> with(r, 1L, "data", "expires_at_ms"));
    assertThat(rules(records)).contains(Rule.QUOTE_VALID_AT_ACCEPT);
  }

  @Test
  void deadlineRespectedCatchesAStepAfterTheCutOff() {
    Map<String, Object> received = find(goodDay(), kind("tx_received", "happy"));
    long at = (Long) received.get("at");
    assertThat(rules(plusFirst(goodDay(), deadline(received, at - 10_000))))
        .contains(Rule.DEADLINE_RESPECTED);
    assertThat(rules(plusFirst(goodDay(), deadline(received, at + 60_000))))
        .doesNotContain(Rule.DEADLINE_RESPECTED);
  }

  @Test
  void marginArithmeticCatchesAMarginThatDoesNotFollowFromTheQuotes() {
    List<Map<String, Object>> records =
        edit(
            goodDay(),
            r -> "guarantee_margin".equals(r.get("kind")),
            r -> with(r, 1L, "data", "margin"));
    assertThat(rules(records)).contains(Rule.MARGIN_ARITHMETIC);
  }

  @Test
  void marginArithmeticHasNothingToCheckWithoutAChosenProvider() {
    List<Map<String, Object>> records =
        drop(
            goodDay(),
            r ->
                List.of("provider_chosen", "tx_economics").contains(r.get("kind"))
                    && "happy".equals(r.get("tx")));
    assertThat(rules(records)).doesNotContain(Rule.MARGIN_ARITHMETIC);
  }

  @Test
  void economicsConsistentCatchesAChosenNetThatIsNotTheQuotes() {
    List<Map<String, Object>> records =
        edit(
            goodDay(),
            kind("tx_economics", "happy"),
            r -> with(r, 1L, "data", "discovery", "chosen_net"));
    assertThat(rules(records)).contains(Rule.ECONOMICS_CONSISTENT);
  }

  @Test
  void economicsIgnoresACompletedTransactionWithNoPriceDiscovery() {
    List<Map<String, Object>> records =
        edit(goodDay(), kind("tx_economics", "happy"), r -> with(r, null, "data", "discovery"));
    assertThat(rules(records)).doesNotContain(Rule.ECONOMICS_CONSISTENT);
  }

  @Test
  void eligibilityCatchesUsingAProviderTheDecisionRuledOut() {
    List<Map<String, Object>> records =
        edit(
            goodDay(),
            kind("tx_received", "happy"),
            r -> with(r, List.of("second"), "data", "eligibility", "allowed_providers"));
    List<Violation> found = violations(records, Rule.ELIGIBILITY_RESPECTED);
    assertThat(found).isNotEmpty();
    assertThat(found).allMatch(v -> v.detail().contains("is not eligible for the"));
    assertThat(found).allMatch(v -> "happy".equals(v.tx()));
    assertThat(found.stream().map(Violation::detail).distinct()).hasSize(found.size());
  }

  @Test
  void eligibilityCatchesCallingAnyoneWhenTheTransactionWasNotPermitted() {
    List<Map<String, Object>> records =
        edit(
            goodDay(),
            kind("tx_received", "happy"),
            r -> {
              Map<String, Object> refused = with(r, false, "data", "eligibility", "permitted?");
              return with(
                  refused,
                  List.of("forward is not permitted in IN"),
                  "data",
                  "eligibility",
                  "reasons");
            });
    List<Violation> found = violations(records, Rule.ELIGIBILITY_RESPECTED);
    assertThat(found).hasSize(1);
    assertThat(found.get(0).detail())
        .contains("although the transaction was not permitted (forward is not permitted in IN)");
  }

  @Test
  void aLogFromBeforeEligibilityWasRecordedIsNotJudgedByIt() {
    List<Map<String, Object>> records =
        edit(goodDay(), kind("tx_received", "happy"), r -> without(r, "eligibility"));
    assertThat(rules(records)).doesNotContain(Rule.ELIGIBILITY_RESPECTED);
  }

  @Test
  void aCompleteReviewResolutionIsFine() {
    List<Map<String, Object>> records =
        resolvedAfter("review", m("outcome", "not_paid", "by", "robin", "note", "checked"));
    assertThat(rules(records)).doesNotContain(Rule.REVIEW_RECORDED);
    assertThat(Replay.fromRecords(records).transactions().get(2).resolution())
        .isEqualTo("not_paid");
  }

  @Test
  void aReviewResolutionWithNoReasonNoNameOrAnUnknownOutcomeIsCaught() {
    List<Map<String, Object>> incomplete =
        List.of(
            m("outcome", "paid", "by", "robin", "note", ""),
            m("outcome", "paid", "by", null, "note", "x"),
            m("outcome", "paid", "by", "   ", "note", "x"),
            m("outcome", "shrug", "by", "robin", "note", "x"));
    for (Map<String, Object> data : incomplete) {
      assertThat(rules(resolvedAfter("review", data)))
          .as(data.toString())
          .contains(Rule.REVIEW_RECORDED);
    }
  }

  @Test
  void aReviewResolutionForATransactionThatNeverWentToReviewIsCaught() {
    List<Map<String, Object>> records =
        resolvedAfter("happy", m("outcome", "paid", "by", "robin", "note", "why not"));
    assertThat(rules(records)).contains(Rule.REVIEW_RECORDED);
  }

  @Test
  void timeOrderedCatchesTimeRunningBackwards() {
    List<Map<String, Object>> records =
        edit(goodDay(), r -> Long.valueOf(6).equals(r.get("n")), r -> with(r, 1L, "at"));
    assertThat(rules(records)).contains(Rule.TIME_ORDERED);
  }

  @Test
  void aTransactionStillOpenIsReportedAsOpenNotAsAViolation() {
    List<Map<String, Object>> records =
        drop(
            goodDay(),
            r ->
                List.of("tx_completed", "tx_economics", "tx_costed").contains(r.get("kind"))
                    && "happy".equals(r.get("tx")));
    Rules.Verdict verdict = Rules.verify(Replay.fromRecords(records));
    assertThat(verdict.outcomes()).containsEntry(Outcome.OPEN, 1L);
    assertThat(rules(records)).doesNotContain(Rule.SINGLE_PAY);
  }

  @Test
  void aViolationIsEvidencedByTheAuditRecordItBreaks() {
    List<Map<String, Object>> records =
        edit(
            goodDay(),
            r -> "guarantee_margin".equals(r.get("kind")),
            r -> with(r, 1L, "data", "margin"));
    Violation violation = violations(records, Rule.MARGIN_ARITHMETIC).get(0);
    assertThat(violation.n())
        .isEqualTo(find(goodDay(), r -> "guarantee_margin".equals(r.get("kind"))).get("n"));
    assertThat(violation.detail()).startsWith("recorded margin 1, recomputed 5000");
  }

  @Test
  void aRuleNameIsItsWireName() {
    assertThat(Rule.SINGLE_PAY.wire()).isEqualTo("single_pay");
  }

  // ---- helpers ------------------------------------------------------------

  private static List<Map<String, Object>> plus(
      List<Map<String, Object>> records, Map<String, Object> extra) {
    List<Map<String, Object>> out = new ArrayList<>(records);
    out.add(extra);
    return out;
  }

  private static List<Map<String, Object>> plusFirst(
      List<Map<String, Object>> records, Map<String, Object> first) {
    List<Map<String, Object>> out = new ArrayList<>();
    out.add(first);
    out.addAll(records);
    return out;
  }

  private static Map<String, Object> deadline(Map<String, Object> received, long deadlineMs) {
    Map<String, Object> copy = new LinkedHashMap<>(received);
    copy.put("kind", "deadline_set");
    copy.put("data", m("deadline_ms", deadlineMs));
    return copy;
  }

  /** A review resolution recorded after everything else, copying the transaction's last record. */
  private static List<Map<String, Object>> resolvedAfter(String tx, Map<String, Object> data) {
    List<Map<String, Object>> records = goodDay();
    long latest = records.stream().mapToLong(r -> (Long) r.get("at")).max().orElse(0);
    Map<String, Object> last =
        records.stream().filter(r -> tx.equals(r.get("tx"))).reduce((a, b) -> b).orElseThrow();
    Map<String, Object> copy = new LinkedHashMap<>(last);
    copy.put("kind", "review_resolved");
    copy.put("actor", "review");
    copy.put("at", latest + 1);
    copy.put("data", data);
    return plus(records, copy);
  }

  private static Map<String, Object> without(Map<String, Object> record, String key) {
    Map<String, Object> data = new LinkedHashMap<>(Fields.data(record));
    data.remove(key);
    return with(record, data, "data");
  }

  private static List<Object> reversed(Object list) {
    List<Object> out = new ArrayList<>(Fields.list(list));
    Collections.reverse(out);
    return out;
  }
}

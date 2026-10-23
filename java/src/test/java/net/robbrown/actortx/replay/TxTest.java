package net.robbrown.actortx.replay;

import static net.robbrown.actortx.replay.ReplayFixtures.goodDay;
import static net.robbrown.actortx.replay.ReplayFixtures.m;
import static net.robbrown.actortx.replay.ReplayFixtures.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.robbrown.actortx.payment.Outcome;
import net.robbrown.actortx.policy.Policy;
import net.robbrown.actortx.provider.Answer;
import net.robbrown.actortx.provider.HealthState;
import net.robbrown.actortx.provider.Quote;
import net.robbrown.actortx.selection.Candidate;
import net.robbrown.actortx.selection.Evaluation;
import net.robbrown.actortx.selection.Observation;
import net.robbrown.actortx.validation.Validated;
import org.junit.jupiter.api.Test;

/** One transaction's records read back: summaries, quotes, rounds, policy and reselection. */
class TxTest {

  private static List<Map<String, Object>> records(String tx) {
    return Replay.fromRecords(goodDay()).byTx().get(tx);
  }

  private static <T> T valueOf(Validated<T> validated) {
    return switch (validated) {
      case Validated.Valid<T> valid -> valid.value();
      case Validated.Invalid<T> invalid -> throw new AssertionError(invalid.errors());
    };
  }

  private static Map<String, Object> receivedOf(String tx) {
    return Tx.first(records(tx), "tx_received");
  }

  @Test
  void aSummaryReadsTheRecordsOfATransaction() {
    TxSummary s = Tx.summarize(records("happy"));
    assertThat(s.id()).isEqualTo("happy");
    assertThat(s.amount()).isEqualTo(100_000L);
    assertThat(s.startedAtMs()).isEqualTo(1_010L);
    assertThat(s.endedAtMs()).isNotNull();
    assertThat(s.outcome()).isEqualTo(Optional.of(Outcome.COMPLETED));
    assertThat(s.phase()).isNull();
    assertThat(s.resolution()).isNull();
    assertThat(s.request()).containsEntry("merchant_id", "acme");
  }

  @Test
  void aSummaryOfNothingIsOpenAndEmpty() {
    TxSummary s = Tx.summarize(List.of());
    assertThat(s.id()).isNull();
    assertThat(s.outcome()).isEqualTo(Optional.empty());
    assertThat(s.provider()).isNull();
    assertThat(s.steps()).isEmpty();
    assertThat(s.net()).isNull();
    assertThat(s.amount()).isNull();
    assertThat(s.request()).isEmpty();
  }

  @Test
  void theLastPaymentPhaseIsReported() {
    List<Map<String, Object>> records =
        List.of(
            phase(1, "payment", "queued"),
            phase(2, "other", "ignored"),
            phase(3, "payment", "paying"));
    assertThat(Tx.summarize(records).phase()).isEqualTo("paying");
  }

  private static Map<String, Object> phase(long n, String actor, String phase) {
    return m("n", n, "at", n, "kind", "x", "tx", "p", "actor", actor, "data", m("phase", phase));
  }

  @Test
  void theProviderIsTheEconomicsOneElseTheLastChosen() {
    List<Map<String, Object>> failover = records("failover");
    assertThat(Tx.finalProvider(failover, Tx.first(failover, "tx_economics"))).isEqualTo("second");
    assertThat(Tx.finalProvider(Tx.all(failover, "provider_chosen"), Map.of())).isEqualTo("second");
    assertThat(Tx.finalProvider(List.of(), Map.of())).isNull();
  }

  @Test
  void succeededOpsCountsOnlyTheProviderAsked() {
    List<Map<String, Object>> failover = records("failover");
    assertThat(Tx.succeededOps(failover, "best")).containsExactly("accept_quote");
    assertThat(Tx.succeededOps(failover, "second"))
        .containsExactly("accept_quote", "authorise", "pay", "settle");
    assertThat(Tx.succeededOps(failover, "nobody")).isEmpty();
  }

  @Test
  void netIsTheConvertedAmountLessTheFeeOfTheRecordedQuote() {
    List<Map<String, Object>> happy = records("happy");
    assertThat(Tx.netOf(happy, null)).isNull();
    assertThat(Tx.netOf(happy, "nobody")).isNull();
    assertThat(Tx.netOf(happy, "best")).isEqualTo(110_000L);
    assertThat(Tx.quoteFor(happy, "best")).isPresent();
    assertThat(Tx.quoteFor(happy, "nobody")).isEmpty();
    assertThat(Tx.quoteFor(happy, "best").orElseThrow().rate()).isEqualTo(110_000_000L);
  }

  @Test
  void aRecordedQuoteIsRebuiltAsItWasGiven() {
    Map<String, Object> d =
        m("provider", "p", "request_id", "r", "rate", 2L, "fee", 3L, "expires_at_ms", 4L);
    assertThat(Tx.quoteFrom(d)).isEqualTo(new Quote("p", "r", 2L, 3L, 4L));
    assertThat(Tx.quoteFrom(Map.of()).rate()).isZero();
  }

  @Test
  void roundsStartAtEachSelectStartedAndHoldTheirQuotesAndSelection() {
    List<Map<String, Object>> records =
        List.of(
            m("kind", "quote_observed", "n", 1L),
            m("kind", "select_started", "n", 2L),
            m("kind", "quote_observed", "n", 3L),
            m("kind", "other", "n", 4L),
            m("kind", "selection_made", "n", 5L),
            m("kind", "select_started", "n", 6L),
            m("kind", "selection_made", "n", 7L));
    List<Tx.Round> rounds = Tx.rounds(records);
    assertThat(rounds).hasSize(2);
    assertThat(rounds.get(0).records).hasSize(1);
    assertThat(rounds.get(0).selection.get("n")).isEqualTo(5L);
    assertThat(rounds.get(1).records).isEmpty();
    assertThat(rounds.get(1).selection.get("n")).isEqualTo(7L);
    assertThat(Tx.firstRound(List.of()).selection).isEmpty();
  }

  @Test
  void everyKindOfAnswerIsObservedAsTheSelectorReadsIt() {
    List<Observation> observed = Tx.observations(answers());
    assertThat(observed.get(0).result()).isInstanceOf(Answer.Ok.class);
    assertThat(observed.get(0).state()).isEqualTo(HealthState.HEALTHY);
    assertThat(observed.get(0).latencyMs()).isEqualTo(5L);
    assertThat(observed.get(0).observedAtMs()).isEqualTo(9L);
    assertThat(observed.get(1).result()).isEqualTo(new Answer.Rejected<>("no"));
    assertThat(observed.get(1).state()).isEqualTo(HealthState.STALLED);
    assertThat(observed.get(2).result()).isEqualTo(new Answer.Error<>("boom"));
    assertThat(observed.get(3).result()).isInstanceOf(Answer.Timeout.class);
    assertThat(observed.get(3).state()).isEqualTo(HealthState.UNKNOWN);
    assertThat(observed.get(3).latencyMs()).isZero();
  }

  private static List<Map<String, Object>> answers() {
    return List.of(
        answer(
            "a",
            m(
                "outcome",
                "quote",
                "rate",
                1L,
                "state",
                "healthy",
                "latency_ms",
                5L,
                "observed_at_ms",
                9L)),
        answer("b", m("outcome", "rejected", "reason", "no", "state", "stalled")),
        answer("c", m("outcome", "error", "reason", "boom")),
        answer("d", m("outcome", "timeout", "state", "bogus")));
  }

  private static Map<String, Object> answer(String provider, Map<String, Object> body) {
    return m("kind", "quote_observed", "data", with(body, provider, "provider"));
  }

  @Test
  void theMixedTransactionsAnswersAreAllRecordedAndOnlyTheQuoteIsRanked() {
    List<Map<String, Object>> mixed = records("mixed");
    assertThat(Tx.observations(mixed).stream().map(o -> o.result().tag()).toList())
        .containsExactly("ok", "rejected", "error", "timeout");
    Evaluation judged = valueOf(Tx.reselect(mixed, Map.of()));
    assertThat(judged.ranked()).extracting(Candidate::providerId).containsExactly("a");
    assertThat(judged.excluded()).hasSize(3);
  }

  @Test
  void aPolicyWithNoOverridesIsTheRecordedOneExactly() {
    Policy policy = valueOf(Tx.policy(receivedOf("happy"), Map.of()));
    assertThat(policy.weights().cost()).isEqualTo(1.0);
    assertThat(policy.maxBpsFromBest()).isEqualTo(1_000.0);
    assertThat(policy.maxLatencyMs()).isNull();
    assertThat(policy.excludeStates()).isEmpty();
  }

  @Test
  void overridesReplaceTheirKeysAndAreValidatedLikeANewPolicy() {
    Policy changed = valueOf(Tx.policy(receivedOf("happy"), Map.of("max_bps_from_best", 25.0)));
    assertThat(changed.maxBpsFromBest()).isEqualTo(25.0);

    Validated<Policy> refused = Tx.policy(receivedOf("happy"), Map.of("max_bps_from_best", -1));
    assertThat(refused).isInstanceOf(Validated.Invalid.class);
    assertThat(((Validated.Invalid<Policy>) refused).errors().get(0)).contains("max_bps_from_best");
  }

  @Test
  void theRecordedStatesAndLimitsAreReadBack() {
    Map<String, Object> received =
        m(
            "data",
            m(
                "policy",
                m(
                    "weights",
                    m("cost", 1, "speed", 2, "reliability", 3),
                    "min_validity_ms",
                    7,
                    "max_latency_ms",
                    8,
                    "max_bps_from_best",
                    9.5,
                    "exclude_states",
                    List.of("stalled", "bogus"))));
    Policy policy = Tx.exactPolicy(received);
    assertThat(policy.excludeStates())
        .containsExactlyInAnyOrder(HealthState.STALLED, HealthState.UNKNOWN);
    assertThat(policy.minValidityMs()).isEqualTo(7L);
    assertThat(policy.maxLatencyMs()).isEqualTo(8L);
    assertThat(policy.maxBpsFromBest()).isEqualTo(9.5);
    assertThat(policy.weights().speed()).isEqualTo(2.0);
  }

  @Test
  void reselectJudgesTheFirstRoundUnderThePolicyAndRefusesAnInvalidOne() {
    Evaluation judged = valueOf(Tx.reselect(records("happy"), Map.of()));
    assertThat(judged.ranked())
        .extracting(Candidate::providerId)
        .containsExactly("best", "second", "third");

    Validated<Evaluation> refused = Tx.reselect(records("happy"), Map.of("max_bps_from_best", -1));
    assertThat(refused).isInstanceOf(Validated.Invalid.class);
    assertThat(Tx.reselect(records("happy"), Map.of("max_bps_from_best", 0.0)))
        .isInstanceOf(Validated.Valid.class);
  }

  @Test
  void aJudgementThatMustBeValidThrowsWhenItIsNot() {
    Validated<Evaluation> refused =
        new Validated.Invalid<>(List.of("max_bps_from_best: must be nil or a non-negative number"));
    ReplayException thrown = catchThrowableOfType(() -> Tx.valid(refused), ReplayException.class);
    assertThat(thrown.reason()).isEqualTo(ReplayException.Reason.INVALID_POLICY);
    assertThat(thrown.getMessage()).contains("max_bps_from_best");
  }

  @Test
  void reselectingARoundUsesTheRecordedPolicyExactly() {
    Evaluation judged = Tx.reselectRound(receivedOf("happy"), Tx.firstRound(records("happy")));
    assertThat(judged.ranked()).hasSize(3);
  }

  @Test
  void anUndoExistsForEachReversibleStepOnly() {
    assertThat(Tx.undoFor("accept_quote")).contains("cancel_accept");
    assertThat(Tx.undoFor("authorise")).contains("void_authorisation");
    assertThat(Tx.undoFor("pay")).contains("refund");
    assertThat(Tx.undoFor("settle")).isEmpty();
    assertThat(Tx.undoFor(null)).isEmpty();
  }

  @Test
  void frequenciesCountEachOutcome() {
    List<TxSummary> summaries = Replay.fromRecords(goodDay()).transactions();
    assertThat(Tx.frequencies(summaries)).containsEntry(Optional.of(Outcome.COMPLETED), 3L);
  }

  @Test
  void anUnreadableRecordHasNoAmountAndAnUnknownStateIsUnknown() {
    assertThat(Tx.amountOf(Map.of())).isZero();
    assertThat(Tx.state("healthy")).isEqualTo(HealthState.HEALTHY);
    assertThat(Tx.state("nonsense")).isEqualTo(HealthState.UNKNOWN);
  }

  @Test
  void aDayKnowsItsTransactionsAndTheirSummaries() {
    Day day = Replay.fromRecords(goodDay());
    assertThat(day.txOrder()).hasSize(5);
    assertThat(day.head()).isEmpty();
    assertThat(day.tx("mixed").orElseThrow().net()).isEqualTo(550_000L);
    assertThat(day.records()).hasSize(goodDay().size());
  }
}

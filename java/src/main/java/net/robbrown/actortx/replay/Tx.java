package net.robbrown.actortx.replay;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.robbrown.actortx.money.Money;
import net.robbrown.actortx.policy.Policy;
import net.robbrown.actortx.policy.Weights;
import net.robbrown.actortx.provider.Answer;
import net.robbrown.actortx.provider.HealthState;
import net.robbrown.actortx.provider.Quote;
import net.robbrown.actortx.selection.Evaluation;
import net.robbrown.actortx.selection.Observation;
import net.robbrown.actortx.selection.Selector;
import net.robbrown.actortx.validation.Validated;

/**
 * Reads one transaction's records: a summary, its quotes, its selection rounds, and the selection
 * judged again. Pure functions over the decoded records; used by replay, the rules, what-if and
 * comparison.
 */
final class Tx {

  /** The steps a completed transaction runs at its provider, in order. */
  static final List<String> EXPECTED_STEPS = List.of("accept_quote", "authorise", "pay", "settle");

  private static final Map<String, String> UNDO =
      Map.of("accept_quote", "cancel_accept", "authorise", "void_authorisation", "pay", "refund");

  private Tx() {}

  /** One selection round: the quotes observed after a select_started, and the selection made. */
  static final class Round {
    final List<Map<String, Object>> records = new ArrayList<>();
    Map<String, Object> selection = Map.of();
  }

  /**
   * Summarise a transaction's records, as of the last record given.
   *
   * @param records the transaction's records, in order
   * @return the summary
   */
  static TxSummary summarize(List<Map<String, Object>> records) {
    Map<String, Object> received = first(records, "tx_received");
    Map<String, Object> terminal =
        records.stream()
            .filter(r -> Outcome.isTerminal(r.get("kind")))
            .findFirst()
            .orElse(Map.of());
    Map<String, Object> economics = first(records, "tx_economics");
    String provider = finalProvider(records, economics);
    Map<String, Object> request = Fields.map(Fields.data(received).get("request"));
    return new TxSummary(
        Fields.str(received.get("tx")),
        request,
        Fields.lng(request.get("amount")),
        Fields.lng(received.get("at")),
        Fields.lng(terminal.get("at")),
        Outcome.forTerminal(terminal.get("kind")),
        phase(records),
        provider,
        succeededOps(records, provider),
        netOf(records, provider),
        Fields.lng(Fields.data(economics).get("margin")),
        Fields.str(Fields.data(lastOf(records, "review_resolved")).get("outcome")));
  }

  /** Records of one kind, in order. */
  static List<Map<String, Object>> all(List<Map<String, Object>> records, String kind) {
    return records.stream().filter(r -> Fields.is(r, kind)).toList();
  }

  /** The first record of a kind, or an empty map. */
  static Map<String, Object> first(List<Map<String, Object>> records, String kind) {
    return records.stream().filter(r -> Fields.is(r, kind)).findFirst().orElse(Map.of());
  }

  /** The last record of a kind, or an empty map. */
  static Map<String, Object> lastOf(List<Map<String, Object>> records, String kind) {
    return Fields.last(all(records, kind));
  }

  /** The operations that succeeded at a provider (a direct success or a reconcile), in order. */
  static List<String> succeededOps(List<Map<String, Object>> records, String provider) {
    return records.stream()
        .filter(
            r ->
                Fields.oneOf(r.get("kind"), "step_succeeded", "reconciled")
                    && Objects.equals(Fields.data(r).get("provider"), provider))
        .map(r -> Fields.str(Fields.data(r).get("op")))
        .toList();
  }

  /** The provider the transaction ended with: the economics' one, else the last one chosen. */
  static String finalProvider(List<Map<String, Object>> records, Map<String, Object> economics) {
    String recorded = Fields.str(Fields.data(economics).get("provider"));
    return recorded != null
        ? recorded
        : Fields.str(Fields.data(lastOf(records, "provider_chosen")).get("provider"));
  }

  /** The undo operation that reverses a step, if there is one. */
  static Optional<String> undoFor(String op) {
    return Optional.ofNullable(op).map(UNDO::get);
  }

  /**
   * Net value (minor units) of a provider's recorded quote for this transaction.
   *
   * @return the value, or null when there is no provider or no quote from it
   */
  static Long netOf(List<Map<String, Object>> records, String provider) {
    if (provider == null) {
      return null;
    }
    return quoteFor(records, provider)
        .map(q -> Money.convert(amountOf(first(records, "tx_received")), q.rate()) - q.fee())
        .orElse(null);
  }

  /** The recorded quote of a provider, rebuilt as a {@link Quote}. */
  static Optional<Quote> quoteFor(List<Map<String, Object>> records, String provider) {
    return quotePayloads(records).stream()
        .filter(d -> Objects.equals(d.get("provider"), provider))
        .findFirst()
        .map(Tx::quoteFrom);
  }

  /** A {@link Quote} from a recorded {@code quote_observed} payload. */
  static Quote quoteFrom(Map<String, Object> d) {
    return new Quote(
        Fields.str(d.get("provider")),
        Fields.str(d.get("request_id")),
        Fields.lngOr(d.get("rate"), 0),
        Fields.lngOr(d.get("fee"), 0),
        Fields.lngOr(d.get("expires_at_ms"), 0));
  }

  /** Selection rounds, in order. A round starts at each select_started. */
  static List<Round> rounds(List<Map<String, Object>> records) {
    List<Round> rounds = new ArrayList<>();
    for (Map<String, Object> record : records) {
      if (Fields.is(record, "select_started")) {
        rounds.add(new Round());
      } else if (!rounds.isEmpty()) {
        attach(rounds.get(rounds.size() - 1), record);
      }
    }
    return rounds;
  }

  /** The records of the first round, or an empty round if there is none. */
  static Round firstRound(List<Map<String, Object>> records) {
    return rounds(records).stream().findFirst().orElseGet(Round::new);
  }

  /**
   * Observations for {@link Selector#evaluate}, rebuilt from the {@code quote_observed} records.
   */
  static List<Observation> observations(List<Map<String, Object>> records) {
    return all(records, "quote_observed").stream().map(Tx::observation).toList();
  }

  /**
   * The policy recorded for the transaction. With no overrides it is rebuilt exactly as recorded
   * (weights are not renormalised, so scores reproduce to the last bit). With overrides, each
   * top-level key replaces the recorded one and the result is validated like any new policy.
   *
   * @param received the tx_received record
   * @param overrides keys to change, possibly empty
   * @return the policy, or every reason it is not valid
   */
  static Validated<Policy> policy(Map<String, Object> received, Map<String, Object> overrides) {
    if (overrides.isEmpty()) {
      return new Validated.Valid<>(exactPolicy(received));
    }
    Map<String, Object> merged = new LinkedHashMap<>(recordedPolicy(received));
    merged.putAll(overrides);
    return Policy.fromMap(merged);
  }

  /** The policy exactly as recorded, rebuilt without validation or renormalisation. */
  static Policy exactPolicy(Map<String, Object> received) {
    Map<String, Object> recorded = recordedPolicy(received);
    Map<String, Object> weights = Fields.map(recorded.get("weights"));
    Set<HealthState> excluded =
        Fields.strings(recorded.get("exclude_states")).stream()
            .map(Tx::state)
            .collect(Collectors.toSet());
    return new Policy(
        new Weights(
            Fields.dblOr(weights.get("cost"), 0),
            Fields.dblOr(weights.get("speed"), 0),
            Fields.dblOr(weights.get("reliability"), 0)),
        Fields.lngOr(recorded.get("min_validity_ms"), 0),
        Fields.lng(recorded.get("max_latency_ms")),
        Fields.dbl(recorded.get("max_bps_from_best")),
        excluded);
  }

  /**
   * Re-run the live selection rule over the recorded observations of the first round, under the
   * policy with the overrides applied.
   *
   * @param records the transaction's records
   * @param overrides keys to change, possibly empty
   * @return the judgement, or every reason the policy is not valid
   */
  static Validated<Evaluation> reselect(
      List<Map<String, Object>> records, Map<String, Object> overrides) {
    Map<String, Object> received = first(records, "tx_received");
    return switch (policy(received, overrides)) {
      case Validated.Valid<Policy> valid ->
          new Validated.Valid<>(judge(received, firstRound(records), valid.value()));
      case Validated.Invalid<Policy> invalid -> new Validated.Invalid<>(invalid.errors());
    };
  }

  /** Judge one round's observations under the recorded policy, exactly as recorded. */
  static Evaluation reselectRound(Map<String, Object> received, Round round) {
    return judge(received, round, exactPolicy(received));
  }

  /**
   * The value of a judgement that must be valid.
   *
   * @param judged a judgement
   * @return the judgement
   * @throws ReplayException if the policy it was made under is not valid
   */
  static Evaluation valid(Validated<Evaluation> judged) {
    return switch (judged) {
      case Validated.Valid<Evaluation> valid -> valid.value();
      case Validated.Invalid<Evaluation> invalid ->
          throw new ReplayException(
              ReplayException.Reason.INVALID_POLICY, String.join("; ", invalid.errors()));
    };
  }

  /** How many of each outcome, for a set of transactions. */
  static Map<Outcome, Long> frequencies(Collection<TxSummary> summaries) {
    return summaries.stream()
        .collect(Collectors.groupingBy(TxSummary::outcome, Collectors.counting()));
  }

  /** The amount being converted, from the received record (zero if absent). */
  static long amountOf(Map<String, Object> received) {
    return Fields.lngOr(Fields.dig(received, "data", "request", "amount"), 0);
  }

  /** The health state a recorded name stands for; unknown names are {@code UNKNOWN}. */
  static HealthState state(Object name) {
    return HealthState.parse(name).orElse(HealthState.UNKNOWN);
  }

  private static Map<String, Object> recordedPolicy(Map<String, Object> received) {
    return Fields.map(Fields.data(received).get("policy"));
  }

  private static String phase(List<Map<String, Object>> records) {
    String phase = null;
    for (Map<String, Object> record : records) {
      String value = Fields.str(Fields.data(record).get("phase"));
      if ("payment".equals(record.get("actor")) && value != null) {
        phase = value;
      }
    }
    return phase;
  }

  private static List<Map<String, Object>> quotePayloads(List<Map<String, Object>> records) {
    return all(records, "quote_observed").stream()
        .map(Fields::data)
        .filter(d -> "quote".equals(d.get("outcome")))
        .toList();
  }

  private static void attach(Round round, Map<String, Object> record) {
    if (Fields.is(record, "quote_observed")) {
      round.records.add(record);
    } else if (Fields.is(record, "selection_made")) {
      round.selection = record;
    }
  }

  private static Observation observation(Map<String, Object> record) {
    Map<String, Object> d = Fields.data(record);
    return new Observation(
        Fields.str(d.get("provider")),
        state(d.get("state")),
        answer(d),
        Fields.lngOr(d.get("latency_ms"), 0),
        Fields.lngOr(d.get("observed_at_ms"), 0));
  }

  private static Answer<Quote> answer(Map<String, Object> d) {
    String reason = Objects.toString(d.get("reason"), "");
    return switch (Objects.toString(d.get("outcome"), "")) {
      case "quote" -> new Answer.Ok<>(quoteFrom(d));
      case "rejected" -> new Answer.Rejected<>(reason);
      case "error" -> new Answer.Error<>(reason);
      default -> new Answer.Timeout<>();
    };
  }

  private static Evaluation judge(Map<String, Object> received, Round round, Policy policy) {
    return Selector.evaluate(amountOf(received), observations(round.records), policy);
  }
}

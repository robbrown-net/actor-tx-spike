package net.robbrown.actortx.selection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.robbrown.actortx.money.Money;
import net.robbrown.actortx.policy.Policy;
import net.robbrown.actortx.policy.Policy.TooDear;
import net.robbrown.actortx.policy.Policy.WithinBest;
import net.robbrown.actortx.policy.criteria.Criteria;
import net.robbrown.actortx.policy.criteria.Criterion;
import net.robbrown.actortx.policy.criteria.CriterionInput;
import net.robbrown.actortx.policy.criteria.Span;
import net.robbrown.actortx.provider.Answer;
import net.robbrown.actortx.provider.Answer.Error;
import net.robbrown.actortx.provider.Answer.Ok;
import net.robbrown.actortx.provider.Answer.Rejected;
import net.robbrown.actortx.provider.Quote;
import net.robbrown.actortx.selection.Evaluation.Item;
import net.robbrown.actortx.selection.Evaluation.Item.Accepted;
import net.robbrown.actortx.selection.Evaluation.Item.Failed;
import net.robbrown.actortx.selection.Evaluation.Item.Refused;

/**
 * The pure heart of selection: what each provider answered, judged under a policy. No processes, no
 * clock, no events, so a recorded day can be judged again with the same code that made the live
 * decision.
 */
public final class Selector {

  private static final double ONE_DECIMAL = 10.0;

  private Selector() {}

  /**
   * Judge observations under a policy, with the standard criteria.
   *
   * @param amount the amount being converted, minor units
   * @param observations what each provider answered
   * @param policy the rules
   * @return the per-provider judgements, the ranking and every exclusion
   */
  public static Evaluation evaluate(long amount, List<Observation> observations, Policy policy) {
    return evaluate(amount, observations, policy, Criteria.standard());
  }

  /**
   * Judge observations under a policy, scoring with the given criteria.
   *
   * @param amount the amount being converted, minor units
   * @param observations what each provider answered
   * @param policy the rules; a criterion it does not weigh counts for nothing
   * @param criteria the registry the scores are summed over
   * @return the per-provider judgements, the ranking and every exclusion
   */
  public static Evaluation evaluate(
      long amount, List<Observation> observations, Policy policy, Criteria criteria) {
    List<Item> items = observations.stream().map(o -> judge(o, amount, policy)).toList();
    WithinBest within = policy.withinBest(accepted(items));
    List<Excluded> excluded =
        Stream.concat(refusals(items), within.dropped().stream().map(d -> tooDear(d))).toList();
    return new Evaluation(items, rank(within.kept(), policy, criteria), excluded);
  }

  /**
   * Scores (higher is better) for the candidates, in the same order: for each candidate, the sum of
   * each criterion's score times the policy's weight for it. Empty in, empty out.
   */
  public static List<Double> scores(List<Candidate> candidates, Policy policy, Criteria criteria) {
    if (candidates.isEmpty()) {
      return List.of();
    }
    LongSummaryStatistics nets = candidates.stream().mapToLong(Candidate::net).summaryStatistics();
    LongSummaryStatistics latencies =
        candidates.stream().mapToLong(Candidate::latencyMs).summaryStatistics();
    Span net = new Span(nets.getMin(), nets.getMax());
    Span latency = new Span(latencies.getMin(), latencies.getMax());
    return candidates.stream()
        .map(c -> score(policy, criteria, new CriterionInput(c, net, latency, Map.of())))
        .toList();
  }

  private static double score(Policy policy, Criteria criteria, CriterionInput input) {
    double total = 0.0;
    for (Criterion criterion : criteria.all()) {
      total += policy.weights().get(criterion.name()) * criterion.score(input);
    }
    return total;
  }

  private static Item judge(Observation observation, long amount, Policy policy) {
    if (observation.result() instanceof Ok<Quote> ok) {
      return judgeQuote(observation, ok.value(), amount, policy);
    }
    return new Failed(observation, failure(observation.result()));
  }

  private static Item judgeQuote(Observation observation, Quote quote, long amount, Policy policy) {
    Candidate candidate =
        new Candidate(
            observation.providerId(),
            quote,
            Money.convert(amount, quote.rate()) - quote.fee(),
            observation.latencyMs(),
            quote.expiresAtMs() - observation.observedAtMs(),
            observation.state(),
            0);
    Optional<Policy.Exclusion> exclusion = policy.check(candidate);
    return exclusion
        .<Item>map(e -> new Refused(candidate, wire(e)))
        .orElse(new Accepted(candidate));
  }

  private static String failure(Answer<Quote> answer) {
    return switch (answer) {
      case Rejected<Quote> rejected -> "rejected: " + rejected.reason();
      case Error<Quote> error -> "error: " + error.reason();
      default -> "timeout";
    };
  }

  private static String wire(Policy.Exclusion exclusion) {
    return exclusion.name().toLowerCase(Locale.ROOT);
  }

  private static List<Candidate> accepted(List<Item> items) {
    return items.stream()
        .filter(Accepted.class::isInstance)
        .map(item -> ((Accepted) item).candidate())
        .toList();
  }

  // Why each judged-out provider was out, in the order they were asked.
  private static Stream<Excluded> refusals(List<Item> items) {
    return items.stream().map(Selector::refusal).flatMap(Optional::stream);
  }

  private static Optional<Excluded> refusal(Item item) {
    return switch (item) {
      case Accepted accepted -> Optional.empty();
      case Refused refused ->
          Optional.of(new Excluded(refused.candidate().providerId(), refused.reason()));
      case Failed failed ->
          Optional.of(new Excluded(failed.observation().providerId(), failed.reason()));
    };
  }

  private static Excluded tooDear(TooDear dear) {
    double rounded = Math.round(dear.bps() * ONE_DECIMAL) / ONE_DECIMAL;
    return new Excluded(dear.candidate().providerId(), "worse_than_best_bps: " + rounded);
  }

  private static List<Candidate> rank(
      List<Candidate> candidates, Policy policy, Criteria criteria) {
    List<Double> scores = scores(candidates, policy, criteria);
    List<Candidate> scored =
        new ArrayList<>(
            IntStream.range(0, candidates.size())
                .mapToObj(i -> candidates.get(i).withScore(scores.get(i)))
                .toList());
    scored.sort(
        Comparator.comparingDouble((Candidate c) -> -c.score())
            .thenComparing(Candidate::providerId));
    return scored;
  }
}

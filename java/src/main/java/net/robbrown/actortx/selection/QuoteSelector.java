package net.robbrown.actortx.selection;

import java.util.List;
import net.robbrown.actortx.events.EventSink;
import net.robbrown.actortx.policy.Policy;
import net.robbrown.actortx.provider.HealthState;
import net.robbrown.actortx.provider.QuoteRequest;
import net.robbrown.actortx.selection.QuoteFanOut.Target;

/**
 * Quote-and-select: fan a quote request out to providers concurrently within a deadline, drop those
 * that are unusable, score the rest under a {@link Policy}, and return the best so far.
 *
 * <p>Providers that do not carry the request's currency pair are not asked; nor are providers whose
 * health state the policy excludes (saves network and quota). The deadline is a hard bound: late
 * providers count as timeouts and the health model learns from it.
 */
public final class QuoteSelector implements AutoCloseable {

  /** Used when the caller gives no deadline. */
  public static final long DEFAULT_DEADLINE_MS = 1_000;

  private final QuoteFanOut fanOut = new QuoteFanOut();
  private final Narrator narrator;

  /** A selector that narrates to {@code events}. */
  public QuoteSelector(EventSink events) {
    this.narrator = new Narrator(events);
  }

  /**
   * Select.
   *
   * @param request what to price (its amount is in minor units)
   * @param providers who might be asked
   * @param policy how to judge the answers
   * @param deadlineMs how long providers have
   * @param baseline the caller's fallback route, or {@code null}
   */
  public Selection select(
      QuoteRequest request,
      List<Participant> providers,
      Policy policy,
      long deadlineMs,
      String baseline) {
    long started = System.nanoTime();
    narrator.started(request, providers, deadlineMs);
    Screened screened = screen(request, providers, policy);

    List<Observation> observations = fanOut.observe(request, screened.callable(), deadlineMs);
    observations.forEach(o -> narrator.observation(request, o));

    Evaluation evaluation = Selector.evaluate(amountOf(request), observations, policy);
    evaluation.items().forEach(item -> narrator.item(request, item));

    Selection selection = build(evaluation, screened.notCalled(), baseline, elapsedMs(started));
    narrator.outcome(request, selection);
    return selection;
  }

  private record Screened(List<Target> callable, List<Excluded> notCalled) {}

  // Who is not asked at all, and why: no currency pair, or a health state the policy excludes.
  private Screened screen(QuoteRequest request, List<Participant> providers, Policy policy) {
    List<Participant> capable =
        providers.stream()
            .filter(p -> p.carries(request.sourceCurrency(), request.destinationCurrency()))
            .toList();
    List<Excluded> unsupported =
        providers.stream()
            .filter(p -> !capable.contains(p))
            .peek(p -> narrator.unsupported(request, p))
            .map(p -> new Excluded(p.id(), "unsupported: " + request.pair()))
            .toList();

    List<Target> callable = new java.util.ArrayList<>();
    List<Excluded> skipped = new java.util.ArrayList<>();
    for (Participant provider : capable) {
      HealthState state = provider.source().state();
      if (policy.excludesState(state)) {
        narrator.skipped(request, provider, state);
        skipped.add(new Excluded(provider.id(), "state: " + state.wire()));
      } else {
        callable.add(new Target(provider, state));
      }
    }
    return new Screened(
        callable, java.util.stream.Stream.concat(unsupported.stream(), skipped.stream()).toList());
  }

  private static Selection build(
      Evaluation evaluation, List<Excluded> notCalled, String baseline, long elapsedMs) {
    List<Excluded> excluded =
        java.util.stream.Stream.concat(notCalled.stream(), evaluation.excluded().stream()).toList();
    return new Selection(
        Selection.decide(evaluation.ranked(), baseline), evaluation.ranked(), excluded, elapsedMs);
  }

  private static long amountOf(QuoteRequest request) {
    return request.amount() == null ? 0 : request.amount();
  }

  private static long elapsedMs(long startedNanos) {
    return (System.nanoTime() - startedNanos) / 1_000_000;
  }

  @Override
  public void close() {
    fanOut.close();
  }
}

package net.robbrown.actortx.selection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.robbrown.actortx.events.EventSink;
import net.robbrown.actortx.money.Money;
import net.robbrown.actortx.provider.Answer;
import net.robbrown.actortx.provider.Answer.Error;
import net.robbrown.actortx.provider.Answer.Ok;
import net.robbrown.actortx.provider.Answer.Rejected;
import net.robbrown.actortx.provider.HealthState;
import net.robbrown.actortx.provider.Quote;
import net.robbrown.actortx.provider.QuoteRequest;
import net.robbrown.actortx.selection.Evaluation.Item;
import net.robbrown.actortx.selection.Evaluation.Item.Accepted;
import net.robbrown.actortx.selection.Evaluation.Item.Failed;
import net.robbrown.actortx.selection.Evaluation.Item.Refused;
import net.robbrown.actortx.selection.Selection.Outcome;
import net.robbrown.actortx.selection.Selection.Outcome.Fallback;
import net.robbrown.actortx.selection.Selection.Outcome.Selected;

/**
 * Tells the story of a selection: the events people read, and the journal records a replay reads.
 * Everything here is a side effect; the decision itself lives in {@link Selector#evaluate}, which
 * has none.
 */
final class Narrator {

  private static final double THREE_DECIMALS = 1000.0;

  private final EventSink events;

  Narrator(EventSink events) {
    this.events = events;
  }

  /** Selection began: who is being asked, and how long they have. */
  void started(QuoteRequest request, List<Participant> providers, long deadlineMs) {
    String names = providers.stream().map(Participant::id).collect(Collectors.joining(", "));
    announce(
        request,
        "select_started",
        "asking %d provider(s) for quotes (deadline %dms): %s"
            .formatted(providers.size(), deadlineMs, names),
        Map.of(
            "providers",
            providers.stream().map(Participant::id).toList(),
            "deadline_ms",
            deadlineMs));
  }

  /** A provider was not called because it does not carry the request's currency pair. */
  void unsupported(QuoteRequest request, Participant provider) {
    String pair = request.sourceCurrency() + " to " + request.destinationCurrency();
    announce(
        request,
        "provider_skipped",
        "not calling %s: it does not carry %s".formatted(provider.id(), pair),
        Map.of("provider", provider.id(), "reason", "unsupported_pair"));
  }

  /** A provider was not called because its health state is excluded. */
  void skipped(QuoteRequest request, Participant provider, HealthState state) {
    announce(
        request,
        "provider_skipped",
        "not calling %s: it is %s".formatted(provider.id(), state.wire()),
        Map.of("provider", provider.id(), "state", state.wire()));
  }

  /** The journal: the raw answer of one provider, with what a replay needs to judge it again. */
  void observation(QuoteRequest request, Observation observation) {
    Map<String, Object> data = new LinkedHashMap<>(detail(observation.result()));
    String kind =
        observation.result() instanceof Ok
            ? "quote"
            : data.getOrDefault("outcome", "timeout").toString();
    data.put("provider", observation.providerId());
    data.put("outcome", kind);
    data.put("state", observation.state().wire());
    data.put("latency_ms", observation.latencyMs());
    data.put("observed_at_ms", observation.observedAtMs());
    events.emit(
        request.id(),
        "journal",
        "quote_observed",
        "journal: %s answered the quote request (%s)".formatted(observation.providerId(), kind),
        data);
  }

  /** The judgement of one provider's answer. */
  void item(QuoteRequest request, Item item) {
    switch (item) {
      case Accepted accepted -> received(request, accepted.candidate());
      case Refused refused ->
          announce(
              request,
              "quote_excluded",
              "%s's quote is unusable: %s"
                  .formatted(refused.candidate().providerId(), refused.reason()),
              Map.of("provider", refused.candidate().providerId(), "reason", refused.reason()));
      case Failed failed -> failedQuote(request, failed);
    }
  }

  /** What was decided. */
  void outcome(QuoteRequest request, Selection selection) {
    announce(request, "selection_made", outcomeMessage(selection), outcomeData(selection));
  }

  private void received(QuoteRequest request, Candidate c) {
    announce(
        request,
        "quote_received",
        "%s quoted rate %s, fee %s (net %s, %dms)"
            .formatted(
                c.providerId(),
                Money.formatRate(c.quote().rate()),
                Money.format(c.quote().fee()),
                Money.format(c.net()),
                c.latencyMs()),
        Map.of(
            "provider", c.providerId(),
            "rate", c.quote().rate(),
            "fee", c.quote().fee(),
            "latency_ms", c.latencyMs()));
  }

  private void failedQuote(QuoteRequest request, Failed failed) {
    Observation o = failed.observation();
    announce(
        request,
        "quote_failed",
        "%s gave no quote: %s after %dms".formatted(o.providerId(), failed.reason(), o.latencyMs()),
        Map.of("provider", o.providerId(), "reason", failed.reason(), "latency_ms", o.latencyMs()));
  }

  private static String outcomeMessage(Selection selection) {
    return switch (selection.outcome()) {
      case Selected selected -> selectedMessage(selected.best(), selection.ranked());
      case Fallback fallback -> "no usable quote; falling back to baseline " + fallback.baseline();
      case Outcome.None none -> "no usable quote from any provider";
    };
  }

  private static String selectedMessage(Candidate best, List<Candidate> ranked) {
    String head =
        "selected %s (score %s, net %s, %dms)"
            .formatted(best.providerId(), score(best), Money.format(best.net()), best.latencyMs());
    return head + aheadOf(ranked.subList(1, ranked.size()));
  }

  private static String aheadOf(List<Candidate> others) {
    return others.isEmpty()
        ? ""
        : " ahead of "
            + others.stream()
                .map(c -> c.providerId() + " (" + score(c) + ")")
                .collect(Collectors.joining(", "));
  }

  private static String score(Candidate candidate) {
    return Double.toString(Math.round(candidate.score() * THREE_DECIMALS) / THREE_DECIMALS);
  }

  private static Map<String, Object> outcomeData(Selection selection) {
    Map<String, Object> data = new LinkedHashMap<>();
    if (selection.outcome() instanceof Selected selected) {
      data.put("provider", selected.best().providerId());
      data.put("ranking", selection.ranked().stream().map(Narrator::ranking).toList());
    }
    data.put("excluded", selection.excluded().stream().map(Narrator::excluded).toList());
    data.put("elapsed_ms", selection.elapsedMs());
    return data;
  }

  private static Map<String, Object> ranking(Candidate c) {
    return Map.of(
        "provider",
        c.providerId(),
        "score",
        c.score(),
        "net",
        c.net(),
        "latency_ms",
        c.latencyMs());
  }

  private static Map<String, Object> excluded(Excluded e) {
    return Map.of("provider", e.providerId(), "reason", e.reason());
  }

  private static Map<String, Object> detail(Answer<Quote> answer) {
    Map<String, Object> detail = new LinkedHashMap<>();
    switch (answer) {
      case Ok<Quote> ok -> quoteDetail(detail, ok.value());
      case Rejected<Quote> rejected -> reasoned(detail, "rejected", rejected.reason());
      case Error<Quote> error -> reasoned(detail, "error", error.reason());
      default -> detail.put("outcome", "timeout");
    }
    return detail;
  }

  private static void quoteDetail(Map<String, Object> detail, Quote q) {
    detail.put("rate", q.rate());
    detail.put("fee", q.fee());
    detail.put("expires_at_ms", q.expiresAtMs());
    detail.put("request_id", q.requestId());
  }

  private static void reasoned(Map<String, Object> detail, String outcome, String reason) {
    detail.put("outcome", outcome);
    detail.put("reason", reason);
  }

  private void announce(
      QuoteRequest request, String kind, String message, Map<String, Object> data) {
    events.emit(request.id(), "selector", kind, message, data);
  }
}

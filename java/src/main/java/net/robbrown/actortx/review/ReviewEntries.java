package net.robbrown.actortx.review;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import net.robbrown.actortx.events.Event;
import net.robbrown.actortx.payment.PaymentSnapshot;
import net.robbrown.actortx.payment.PaymentSnapshot.HistoryEntry;
import net.robbrown.actortx.review.ReviewEntry.Resolution;
import net.robbrown.actortx.review.ReviewEntry.ReviewAction;
import net.robbrown.actortx.review.ReviewEntry.Status;
import net.robbrown.actortx.service.InvalidInputException;

/**
 * The review queue as pure functions: how an entry opens, what a person's action adds to it, how a
 * decision closes it, and how the queue is rebuilt from the audit log's records. Nothing here does
 * I/O or keeps state, so every rule can be tested without a provider or a clock.
 */
public final class ReviewEntries {

  /** What a person may decide, in the order they are offered. */
  private static final List<String> OUTCOMES = List.of("paid", "not_paid", "written_off");

  /** How many lines of a transaction's story an entry keeps. */
  private static final int STORY_LINES = 30;

  /** The actors whose events are not part of a transaction's story. */
  private static final List<String> SYSTEM_ACTORS = List.of("journal", "review");

  /** How each review record folds into an entry, by its kind. */
  private static final Map<String, BiFunction<ReviewEntry, Event, ReviewEntry>> FOLDS =
      Map.of(
          "review_checked", ReviewEntries::foldChecked,
          "review_retry", ReviewEntries::foldRetry,
          "review_result", ReviewEntries::foldResult,
          "review_resolved", ReviewEntries::foldResolved);

  private ReviewEntries() {}

  /**
   * What is known about a transaction when its review opens, or after a restart. Empty when the
   * transaction is no longer known.
   *
   * @param provider the provider it was using, or {@code null}
   * @param done the steps that succeeded there, in order
   * @param request the transaction's request; empty when not known
   * @param story the last lines of what happened
   */
  public record Known(
      String provider, List<String> done, Map<String, Object> request, List<String> story) {

    /** Nothing is known: no provider, no steps, no request, no story. */
    public static final Known NONE = new Known(null, List.of(), Map.of(), List.of());
  }

  /**
   * The ways a person can close a review, in the order they are offered.
   *
   * @return {@code paid}, {@code not_paid}, {@code written_off}
   */
  public static List<String> outcomes() {
    return OUTCOMES;
  }

  /**
   * A new open entry.
   *
   * @param tx the transaction
   * @param atMs when it entered review
   * @param reason why it needs review
   * @param known what is known about it
   * @return the open entry
   */
  public static ReviewEntry open(String tx, long atMs, String reason, Known known) {
    return new ReviewEntry(
        tx,
        Status.OPEN,
        atMs,
        reason,
        known.provider(),
        known.done(),
        known.request(),
        known.story(),
        List.of(),
        null);
  }

  /**
   * The entry with something a person did added to its trail.
   *
   * @param entry the entry
   * @param atMs when it happened
   * @param kind {@code check}, {@code retry} or {@code result}
   * @param by who did it
   * @param op the operation it concerned, or {@code null}
   * @param detail what was asked or answered
   * @return the entry with the action appended
   */
  public static ReviewEntry addAction(
      ReviewEntry entry, long atMs, String kind, String by, String op, String detail) {
    List<ReviewAction> actions = new ArrayList<>(entry.actions());
    actions.add(new ReviewAction(atMs, kind, by, op, detail));
    return new ReviewEntry(
        entry.tx(),
        entry.status(),
        entry.openedAtMs(),
        entry.reason(),
        entry.provider(),
        entry.done(),
        entry.request(),
        entry.story(),
        List.copyOf(actions),
        entry.resolution());
  }

  /**
   * The entry closed with a decision.
   *
   * @param entry the entry
   * @param atMs when it was decided
   * @param outcome {@code paid}, {@code not_paid} or {@code written_off}
   * @param by who decided
   * @param note why
   * @return the resolved entry
   */
  public static ReviewEntry resolve(
      ReviewEntry entry, long atMs, String outcome, String by, String note) {
    return new ReviewEntry(
        entry.tx(),
        Status.RESOLVED,
        entry.openedAtMs(),
        entry.reason(),
        entry.provider(),
        entry.done(),
        entry.request(),
        entry.story(),
        entry.actions(),
        new Resolution(outcome, by, note, atMs));
  }

  /**
   * Is this a decision the audit log can stand behind: a known outcome, who is deciding, and why?
   *
   * @param outcome the outcome chosen
   * @param by who is deciding
   * @param note why
   * @throws InvalidInputException naming the first thing missing
   */
  public static void checkResolution(String outcome, String by, String note) {
    checkOutcome(outcome);
    given(by, "say who is deciding");
    given(note, "say why: a decision without a reason cannot be audited");
  }

  /**
   * Does a live event say that a transaction has just entered review? Only the payment process says
   * so, and only about a transaction.
   *
   * @param event the event
   * @return true when it opens a review
   */
  public static boolean opens(Event event) {
    return "needs_review".equals(event.kind())
        && "payment".equals(event.actor())
        && event.txId() != null;
  }

  /**
   * The entry details of a live transaction.
   *
   * @param snapshot the transaction as it stands
   * @return what is known about it
   */
  public static Known known(PaymentSnapshot snapshot) {
    List<String> messages = snapshot.history().stream().map(HistoryEntry::message).toList();
    return new Known(snapshot.provider(), snapshot.done(), Map.of(), lastLines(messages));
  }

  /**
   * The queue rebuilt from audit-log records (one or several logs' worth, oldest first): one entry
   * per transaction that has a {@code needs_review} record, with the review actions after it.
   *
   * @param records the records, as events
   * @return the entries by transaction
   */
  public static Map<String, ReviewEntry> rebuild(List<Event> records) {
    Map<String, List<Event>> byTx = new LinkedHashMap<>();
    for (Event event : records) {
      if (event.txId() != null) {
        byTx.computeIfAbsent(event.txId(), k -> new ArrayList<>()).add(event);
      }
    }
    Map<String, ReviewEntry> entries = new LinkedHashMap<>();
    byTx.forEach(
        (tx, events) ->
            firstNeedsReview(events)
                .ifPresent(needs -> entries.put(tx, rebuildOne(needs, events))));
    return entries;
  }

  /**
   * Fold one event into an entry: a review record adds what it recorded; anything else changes
   * nothing.
   *
   * @param entry the entry
   * @param event the event
   * @return the entry, changed or not
   */
  static ReviewEntry apply(ReviewEntry entry, Event event) {
    BiFunction<ReviewEntry, Event, ReviewEntry> fold = FOLDS.get(event.kind());
    return fold == null ? entry : fold.apply(entry, event);
  }

  /**
   * A data field as text, or {@code null} when it is absent.
   *
   * @param data the event's data
   * @param key the field
   * @return its text, or null
   */
  static String text(Map<String, Object> data, String key) {
    Object value = data.get(key);
    return value == null ? null : value.toString();
  }

  private static void checkOutcome(String outcome) {
    if (outcome == null || !OUTCOMES.contains(outcome)) {
      throw input("choose one of: " + String.join(", ", OUTCOMES));
    }
  }

  private static void given(String text, String message) {
    if (text == null || text.isBlank()) {
      throw input(message);
    }
  }

  private static InvalidInputException input(String message) {
    return new InvalidInputException(List.of(message));
  }

  private static ReviewEntry rebuildOne(Event needs, List<Event> events) {
    Tally tally = new Tally();
    events.forEach(tally::add);
    ReviewEntry entry = open(needs.txId(), needs.atMs(), needs.message(), tally.known());
    for (Event event : events) {
      entry = apply(entry, event);
    }
    return entry;
  }

  private static Optional<Event> firstNeedsReview(List<Event> events) {
    return events.stream().filter(e -> "needs_review".equals(e.kind())).findFirst();
  }

  private static ReviewEntry foldChecked(ReviewEntry entry, Event event) {
    return addAction(
        entry,
        event.atMs(),
        "check",
        text(event.data(), "by"),
        null,
        String.valueOf(event.data().get("answers")));
  }

  private static ReviewEntry foldRetry(ReviewEntry entry, Event event) {
    return addAction(
        entry,
        event.atMs(),
        "retry",
        text(event.data(), "by"),
        text(event.data(), "op"),
        "requested");
  }

  private static ReviewEntry foldResult(ReviewEntry entry, Event event) {
    return addAction(
        entry,
        event.atMs(),
        "result",
        text(event.data(), "by"),
        text(event.data(), "op"),
        text(event.data(), "result"));
  }

  private static ReviewEntry foldResolved(ReviewEntry entry, Event event) {
    return resolve(
        entry,
        event.atMs(),
        text(event.data(), "outcome"),
        text(event.data(), "by"),
        text(event.data(), "note"));
  }

  private static Map<String, Object> asMap(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> copy = new LinkedHashMap<>();
      map.forEach((k, v) -> copy.put(String.valueOf(k), v));
      return copy;
    }
    return Map.of();
  }

  private static List<String> lastLines(List<String> lines) {
    int from = Math.max(0, lines.size() - STORY_LINES);
    return Collections.unmodifiableList(new ArrayList<>(lines.subList(from, lines.size())));
  }

  /** Gathers what a transaction's own records say: its provider, steps, request and story. */
  private static final class Tally {

    private final List<String> story = new ArrayList<>();
    private final List<String> done = new ArrayList<>();
    private final Map<String, Consumer<Event>> handlers =
        Map.of(
            "tx_received", this::received,
            "provider_chosen", this::chosen,
            "failover", event -> failedOver(),
            "step_succeeded", this::stepped);
    private String provider;
    private Map<String, Object> request = Map.of();

    void add(Event event) {
      if (!SYSTEM_ACTORS.contains(event.actor())) {
        story.add(event.message());
      }
      Consumer<Event> handler = handlers.get(event.kind());
      if (handler != null) {
        handler.accept(event);
      }
    }

    Known known() {
      return new Known(
          provider, Collections.unmodifiableList(new ArrayList<>(done)), request, lastLines(story));
    }

    private void received(Event event) {
      request = asMap(event.data().get("request"));
    }

    private void chosen(Event event) {
      provider = text(event.data(), "provider");
      done.clear();
    }

    private void failedOver() {
      provider = null;
      done.clear();
    }

    private void stepped(Event event) {
      done.add(text(event.data(), "op"));
    }
  }
}

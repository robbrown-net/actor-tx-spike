package net.robbrown.actortx.review;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.robbrown.actortx.events.EventSink;
import net.robbrown.actortx.provider.Operation;
import net.robbrown.actortx.review.ReviewEntry.Resolution;
import net.robbrown.actortx.service.InvalidInputException;

/**
 * What a person does with a transaction in the review queue: check it with the providers, send a
 * step again under its original key, or resolve it with a decision. Everything is announced as an
 * event (actor {@code review}) and written to the audit log before it takes effect; if the log
 * cannot take the record, nothing is sent and nothing changes.
 *
 * <p>Actions are serialised, as the Elixir review process served them: one at a time. Input
 * problems raise {@link InvalidInputException}; a state the queue does not allow raises {@link
 * ReviewException}. A {@link ReviewException} for a missing or closed review means the caller
 * should show the queue again.
 */
public final class ReviewService {

  private static final String ACTOR = "review";
  private static final String UNAVAILABLE = "the audit log is unavailable, so nothing was done";
  private static final String USAGE =
      "a review command needs do, tx and who is acting (check, retry, resolve)";

  /** The steps a check asks about, in order. */
  private static final List<Operation> CHECKED =
      List.of(Operation.ACCEPT_QUOTE, Operation.AUTHORISE, Operation.PAY, Operation.SETTLE);

  /** The steps a person may send again: each is safe to repeat under its key. */
  private static final List<Operation> RETRYABLE =
      List.of(
          Operation.SETTLE,
          Operation.CANCEL_ACCEPT,
          Operation.VOID_AUTHORISATION,
          Operation.REFUND);

  private static final String RETRYABLE_TEXT =
      RETRYABLE.stream().map(Operation::wire).collect(Collectors.joining(", "));

  /** Each operation's idempotency-key step: an undo shares the key of the step it undoes. */
  private static final Map<Operation, String> STEP =
      Map.of(
          Operation.ACCEPT_QUOTE, "accept",
          Operation.AUTHORISE, "authorise",
          Operation.PAY, "pay",
          Operation.SETTLE, "settle",
          Operation.CANCEL_ACCEPT, "accept",
          Operation.VOID_AUTHORISATION, "authorise",
          Operation.REFUND, "pay");

  /** The fields each dashboard command needs. */
  private static final Map<String, List<String>> REQUIRED =
      Map.of(
          "check", List.of("tx", "by"),
          "retry", List.of("tx", "op", "by"),
          "resolve", List.of("tx", "outcome", "by", "note"));

  private final ReviewQueue queue;
  private final ReviewProviders providers;
  private final EventSink events;
  private final Supplier<Optional<String>> barrier;
  private final LongSupplier clock;

  /**
   * A review service over a queue.
   *
   * @param queue the queue of entries
   * @param providers how to reach the providers
   * @param events where the review's events are announced
   * @param barrier the write-ahead barrier: empty when everything announced so far is durable,
   *     otherwise why it is not
   * @param clock wall-clock milliseconds
   */
  public ReviewService(
      ReviewQueue queue,
      ReviewProviders providers,
      EventSink events,
      Supplier<Optional<String>> barrier,
      LongSupplier clock) {
    this.queue = queue;
    this.providers = providers;
    this.events = events;
    this.barrier = barrier;
    this.clock = clock;
  }

  /**
   * Every entry: open first (oldest first), then resolved (latest first).
   *
   * @return the queue
   */
  public List<ReviewEntry> list() {
    return queue.queue();
  }

  /**
   * One entry, if the transaction is in the queue.
   *
   * @param tx the transaction
   * @return the entry, or empty
   */
  public Optional<ReviewEntry> view(String tx) {
    return queue.find(tx);
  }

  /**
   * Counts and the entries, for the dashboard.
   *
   * @return how many are open and resolved, and the entries
   */
  public ReviewSnapshot snapshot() {
    List<ReviewEntry> all = queue.queue();
    int open = (int) all.stream().filter(ReviewEntry::open).count();
    return new ReviewSnapshot(open, all.size() - open, all);
  }

  /**
   * Ask the providers what they hold for each step of the transaction. Read-only: nothing changes
   * at a provider, and the check is recorded.
   *
   * @param tx the transaction under review
   * @param by who is checking
   * @return what each provider holds, per step
   * @throws InvalidInputException when no one is named
   * @throws ReviewException when the transaction is not open, or its provider is not available
   */
  public synchronized List<CheckAnswer> check(String tx, String by) {
    ReviewEntry entry = acting(tx, by);
    List<CheckAnswer> answers = CHECKED.stream().map(op -> answer(entry, op)).toList();
    List<Map<String, Object>> maps = answers.stream().map(CheckAnswer::toMap).toList();
    durably(
        tx,
        "review_checked",
        "%s checked %s with %s".formatted(by, tx, entry.provider()),
        Map.of("by", by, "answers", maps));
    queue.replace(ReviewEntries.addAction(entry, now(), "check", by, null, String.valueOf(maps)));
    return answers;
  }

  /**
   * Send a step again under its original key. The intent is recorded and made durable first; only
   * then is the step sent, and its result recorded.
   *
   * @param tx the transaction under review
   * @param op the step: settle, cancel_accept, void_authorisation or refund
   * @param by who is retrying
   * @return the step, its key and the provider's answer
   * @throws InvalidInputException when no one is named, or the step may not be sent again
   * @throws ReviewException when the transaction is not open, its provider is not available, or the
   *     audit log cannot take the record
   */
  public synchronized RetryResult retry(String tx, String op, String by) {
    ReviewEntry entry = acting(tx, by);
    Operation step = retryable(op);
    String key = key(entry, step);
    durably(
        tx,
        "review_retry",
        "%s asks to send %s again to %s (key %s)".formatted(by, step.wire(), entry.provider(), key),
        Map.of("by", by, "op", step.wire(), "key", key));
    String result = providers.invoke(entry.provider(), step, key).tag();
    events.emit(
        tx,
        ACTOR,
        "review_result",
        "%s at %s answered %s".formatted(step.wire(), entry.provider(), result),
        Map.of("by", by, "op", step.wire(), "key", key, "result", result));
    ReviewEntry requested =
        ReviewEntries.addAction(entry, now(), "retry", by, step.wire(), "requested");
    queue.replace(ReviewEntries.addAction(requested, now(), "result", by, step.wire(), result));
    return new RetryResult(step.wire(), key, result);
  }

  /**
   * Close a review with a decision. The decision is recorded before it takes effect.
   *
   * @param tx the transaction under review
   * @param outcome {@code paid}, {@code not_paid} or {@code written_off}
   * @param by who is deciding
   * @param note why
   * @return the resolution
   * @throws InvalidInputException when the outcome, who or why is missing or unknown
   * @throws ReviewException when the transaction is not open or the audit log cannot take the
   *     record
   */
  public synchronized Resolution resolve(String tx, String outcome, String by, String note) {
    ReviewEntry entry = openEntry(tx);
    ReviewEntries.checkResolution(outcome, by, note);
    durably(
        tx,
        "review_resolved",
        "%s resolved %s as %s".formatted(by, tx, outcome),
        Map.of("by", by, "outcome", outcome, "note", note));
    ReviewEntry resolved = ReviewEntries.resolve(entry, now(), outcome, by, note);
    queue.replace(resolved);
    return resolved.resolution();
  }

  /**
   * Apply a dashboard command: {@code do} is {@code check}, {@code retry} or {@code resolve}, with
   * the fields that verb needs.
   *
   * @param body the command's fields
   * @return what the verb returned
   * @throws InvalidInputException when the command is not one of these, or a field is missing
   */
  public ReviewReply command(Map<String, Object> body) {
    String verb = ReviewEntries.text(body, "do");
    if (!complete(verb, body)) {
      throw new InvalidInputException(List.of(USAGE));
    }
    return dispatch(verb, body);
  }

  private ReviewReply dispatch(String verb, Map<String, Object> body) {
    return switch (verb) {
      case "check" -> new ReviewReply.Checked(check(text(body, "tx"), text(body, "by")));
      case "retry" ->
          new ReviewReply.Retried(retry(text(body, "tx"), text(body, "op"), text(body, "by")));
      default ->
          new ReviewReply.Resolved(
              resolve(
                  text(body, "tx"), text(body, "outcome"), text(body, "by"), text(body, "note")));
    };
  }

  private static boolean complete(String verb, Map<String, Object> body) {
    return verb != null
        && REQUIRED.containsKey(verb)
        && REQUIRED.get(verb).stream().allMatch(body::containsKey);
  }

  private static String text(Map<String, Object> body, String key) {
    return ReviewEntries.text(body, key);
  }

  // Every action needs an open entry, someone to blame, and a provider that is there.
  private ReviewEntry acting(String tx, String by) {
    ReviewEntry entry = openEntry(tx);
    named(by);
    requireProvider(entry);
    return entry;
  }

  private ReviewEntry openEntry(String tx) {
    ReviewEntry entry =
        queue.find(tx).orElseThrow(() -> new ReviewException(tx + " is not waiting for review"));
    if (!entry.open()) {
      throw new ReviewException(tx + " has already been resolved");
    }
    return entry;
  }

  private static void named(String by) {
    if (by == null || by.isEmpty()) {
      throw new InvalidInputException(
          List.of("say who is acting: everything a reviewer does is recorded under their name"));
    }
  }

  private void requireProvider(ReviewEntry entry) {
    if (entry.provider() == null) {
      throw new ReviewException("no provider was chosen for this transaction");
    }
    if (!providers.available(entry.provider())) {
      throw new ReviewException("provider " + entry.provider() + " is not available");
    }
  }

  private static Operation retryable(String wire) {
    return RETRYABLE.stream()
        .filter(op -> op.wire().equals(wire))
        .findFirst()
        .orElseThrow(
            () ->
                new InvalidInputException(
                    List.of("only these can be sent again: " + RETRYABLE_TEXT)));
  }

  private CheckAnswer answer(ReviewEntry entry, Operation op) {
    String key = key(entry, op);
    return new CheckAnswer(op.wire(), key, providers.status(entry.provider(), op, key).tag());
  }

  private static String key(ReviewEntry entry, Operation op) {
    return entry.tx() + ":" + entry.provider() + ":" + STEP.get(op);
  }

  // Record first and make it durable, then act: if the log cannot take it, nothing happens.
  private void durably(String tx, String kind, String message, Map<String, Object> data) {
    events.emit(tx, ACTOR, kind, message, data);
    if (barrier.get().isPresent()) {
      throw new ReviewException(UNAVAILABLE);
    }
  }

  private long now() {
    return clock.getAsLong();
  }
}

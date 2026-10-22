package net.robbrown.actortx.payment;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import net.robbrown.actortx.eligibility.Decision;
import net.robbrown.actortx.eligibility.Eligibility;
import net.robbrown.actortx.money.Money;
import net.robbrown.actortx.payment.Deadlines.Deadline;
import net.robbrown.actortx.payment.PaymentSnapshot.HistoryEntry;
import net.robbrown.actortx.payment.Result.Completed;
import net.robbrown.actortx.payment.Result.Failed;
import net.robbrown.actortx.payment.Result.GuaranteeOutcome;
import net.robbrown.actortx.payment.Result.NeedsReview;
import net.robbrown.actortx.payment.Result.Summary;
import net.robbrown.actortx.policy.Policy;
import net.robbrown.actortx.provider.Answer;
import net.robbrown.actortx.provider.Operation;
import net.robbrown.actortx.provider.ProviderServer;
import net.robbrown.actortx.provider.Receipt;
import net.robbrown.actortx.selection.Candidate;
import net.robbrown.actortx.selection.Participant;
import net.robbrown.actortx.selection.Selection;
import net.robbrown.actortx.selection.Selection.Outcome;

/**
 * One FX-swap-and-pay transaction.
 *
 * <p>Lifecycle: received, quoting, then for the chosen provider accept, authorise, pay and settle;
 * a definite failure undoes what was done at that provider and fails over to the next-best quote;
 * doubt (a timeout, a lost answer) is resolved by asking the provider, never by guessing.
 *
 * <p>Rules that keep money safe:
 *
 * <ul>
 *   <li><b>Quoting is free; everything after is effectful.</b> Each effectful step has its own
 *       idempotency key {@code "<tx>:<provider>:<step>"}, reused on retry.
 *   <li><b>Write-ahead.</b> The record of a call is made durable (the barrier) before the call is
 *       made. If it cannot be, no money moves: the transaction fails if nothing was done, and goes
 *       to review if something was.
 *   <li><b>Definite failure</b> (the provider said no, or failed technically): retry a bounded
 *       number of times, then compensate what was done there and fail over.
 *   <li><b>Doubt</b>: ask the provider what it holds. For a reversible step still unresolved,
 *       cancel by key and fail over. For pay and settle never fail over while in doubt: after the
 *       last question, stop for a person rather than risk paying twice.
 *   <li><b>Busy provider</b> (its bulkhead was full, so it was never asked): wait and ask again;
 *       this does not use up an attempt, and the deadline bounds the wait.
 *   <li>Compensation that itself cannot be completed also ends in review.
 * </ul>
 *
 * <p>The transaction runs on one (virtual) thread, started with {@link #start()}; everything it
 * does is announced to the event sink.
 */
public final class Payment implements Runnable {

  private static final List<Operation> STEPS =
      List.of(Operation.ACCEPT_QUOTE, Operation.AUTHORISE, Operation.PAY, Operation.SETTLE);
  private static final Map<Operation, Operation> UNDO =
      Map.of(
          Operation.ACCEPT_QUOTE, Operation.CANCEL_ACCEPT,
          Operation.AUTHORISE, Operation.VOID_AUTHORISATION);
  private static final Map<Operation, String> KEY_STEP =
      Map.of(
          Operation.ACCEPT_QUOTE, "accept",
          Operation.AUTHORISE, "authorise",
          Operation.PAY, "pay",
          Operation.SETTLE, "settle");
  private static final Map<Operation, String> VERB =
      Map.of(
          Operation.ACCEPT_QUOTE, "accept the quote",
          Operation.AUTHORISE, "authorise the payment",
          Operation.PAY, "make the payment",
          Operation.SETTLE, "settle the payment",
          Operation.CANCEL_ACCEPT, "cancel the accepted quote",
          Operation.VOID_AUTHORISATION, "void the authorisation",
          Operation.REFUND, "refund");
  private static final Map<Operation, Phase> PHASE =
      Map.of(
          Operation.ACCEPT_QUOTE, Phase.ACCEPTING,
          Operation.AUTHORISE, Phase.AUTHORISING,
          Operation.PAY, Phase.PAYING,
          Operation.SETTLE, Phase.SETTLING);

  /**
   * Who might serve a transaction and how the choice is made.
   *
   * @param providers every provider known
   * @param eligibility the jurisdiction rules
   * @param policy how quotes are judged
   * @param policyLabel which policy layers applied, for the story
   */
  public record Routing(
      List<Participant> providers, Eligibility eligibility, Policy policy, String policyLabel) {}

  /** What happens after a step: carry on, move to the next provider, or the transaction is over. */
  private sealed interface Flow {
    record Next() implements Flow {}

    record Failover(String reason) implements Flow {}

    record Finished() implements Flow {}
  }

  /** What to do about one answer to a step: try again, or this is the outcome. */
  private sealed interface Reaction {
    record Retry(int attempt, long delayMs) implements Reaction {}

    record Final(Flow flow) implements Reaction {}
  }

  /** What follows a compensation. */
  private sealed interface After {
    record FailOver(String reason) implements After {}

    record Abort(String reason) implements After {}
  }

  private final TransactionRequest request;
  private final List<Participant> eligible;
  private final List<Participant> denied;
  private final Decision decision;
  private final Policy policy;
  private final String policyLabel;
  private final PaymentOptions options;
  private final PaymentServices services;
  private final CompletableFuture<Result> finished = new CompletableFuture<>();
  private final Deque<Candidate> candidates = new ArrayDeque<>();
  private final List<Operation> done = new ArrayList<>();
  private final List<HistoryEntry> history = new ArrayList<>();
  private final Map<String, StepHandler> handlers = new java.util.HashMap<>();
  private volatile Phase phase = Phase.RECEIVED;
  private volatile Result result;
  private volatile Candidate current;
  private Deadline deadline;
  private String lastFailure;

  /** One answer to one attempt at a step. */
  private record Call(Operation op, Answer<Receipt> answer, int attempt) {}

  /** Reacts to one kind of answer to a step. */
  @FunctionalInterface
  private interface StepHandler {
    Reaction handle(Call call);
  }

  /**
   * A transaction.
   *
   * @param request what was asked
   * @param routing who might serve it, under which rules and policy
   * @param options retries, timeouts and the baseline route
   * @param services selection, events, the barrier, the clock and the rest
   */
  public Payment(
      TransactionRequest request,
      Routing routing,
      PaymentOptions options,
      PaymentServices services) {
    this.request = request;
    this.decision = routing.eligibility().decide(request.eligibilityMap());
    this.eligible =
        routing.providers().stream()
            .filter(p -> Eligibility.providerAllowed(decision, p.id()))
            .toList();
    this.denied = routing.providers().stream().filter(p -> !eligible.contains(p)).toList();
    this.policy = routing.policy();
    this.policyLabel = routing.policyLabel();
    this.options = options;
    this.services = services;
    registerHandlers();
  }

  /** Begin the transaction on a virtual thread; returns this. */
  public Payment start() {
    Thread.ofVirtual().name("payment-" + request.id()).start(this);
    return this;
  }

  /** The transaction id. */
  public String id() {
    return request.id();
  }

  /** Block until the transaction ends: its result, or empty if it did not end in time. */
  public Optional<Result> await(Duration timeout) {
    try {
      return Optional.of(finished.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
    } catch (TimeoutException | ExecutionException late) {
      return Optional.empty();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  /** The transaction as it stands. */
  public PaymentSnapshot snapshot() {
    synchronized (history) {
      Candidate chosen = current;
      return new PaymentSnapshot(
          id(),
          phase,
          chosen == null ? null : chosen.providerId(),
          done.stream().map(Operation::wire).toList(),
          List.copyOf(history),
          result);
    }
  }

  // ---- the run ---------------------------------------------------------------------------

  @Override
  public void run() {
    try {
      execute();
    } catch (RuntimeException unexpected) {
      if (!phase.terminal()) {
        needsReview(
            "internal_error: " + unexpected,
            "an internal error stopped the transaction: " + unexpected);
      }
    }
  }

  private void execute() {
    note(
        "tx_received",
        "transaction received: amount " + Money.format(request.amount()) + pair(),
        PaymentJournal.received(request, policy.toMap(), options, eligible, denied, decision));
    Optional<Result> refusal = admission();
    if (refusal.isPresent()) {
      finish(refusal.get(), refusal.get() instanceof Failed failed ? failed.reason() : "");
      return;
    }
    announceEligibility();
    announcePolicy();
    announceDeadline();
    phase = Phase.QUOTING;
    route(
        services
            .selector()
            .select(
                request.quoteRequest(),
                eligible,
                policy,
                options.deadlineMs(),
                options.baseline()));
  }

  // " (USD to EUR)" when the request names its currencies
  private String pair() {
    return request.sourceCurrency() == null || request.destinationCurrency() == null
        ? ""
        : " (" + request.sourceCurrency() + " to " + request.destinationCurrency() + ")";
  }

  // Whether the transaction may go ahead at all, decided before any provider is asked.
  private Optional<Result> admission() {
    if (!decision.permitted()) {
      return Optional.of(new Failed("not_permitted: " + String.join("; ", decision.reasons())));
    }
    if (eligible.isEmpty() && !denied.isEmpty()) {
      return Optional.of(new Failed("no_eligible_provider"));
    }
    return Optional.empty();
  }

  private void announceEligibility() {
    if (decision.regions().isEmpty()) {
      return;
    }
    note(
        "eligibility_decided",
        "jurisdiction %s: %d of %d providers eligible; records kept in %s"
            .formatted(
                String.join("/", decision.regions()),
                eligible.size(),
                eligible.size() + denied.size(),
                String.join(", ", decision.logs())),
        Map.of(
            "regions",
            decision.regions(),
            "logs",
            decision.logs(),
            "denied",
            denied.stream().map(Participant::id).toList()));
  }

  private void announcePolicy() {
    if (policyLabel != null && !policyLabel.isEmpty()) {
      note("policy_resolved", "routing policy: " + policyLabel, Map.of("layers", policyLabel));
    }
  }

  private void announceDeadline() {
    long now = services.clock().getAsLong();
    deadline = services.deadlines().forRequest(request, now).orElse(null);
    if (deadline != null) {
      double seconds = Math.round((deadline.atMs() - now) / 100.0) / 10.0;
      note(
          "deadline_set",
          deadline.region() + " cut-off in " + seconds + "s",
          Map.of("region", deadline.region(), "deadline_ms", deadline.atMs(), "seconds", seconds));
    }
  }

  private void route(Selection selection) {
    switch (selection.outcome()) {
      case Outcome.Selected selected -> {
        candidates.addAll(selection.ranked());
        tryCandidates();
      }
      case Outcome.Fallback fallback ->
          finish(
              new Failed("no_quote: fallback " + fallback.baseline()),
              "no usable quote; baseline route "
                  + fallback.baseline()
                  + " is the only option left");
      case Outcome.None none ->
          finish(new Failed("no_quote"), "no provider could give a usable quote");
    }
  }

  // ---- candidates and failover ------------------------------------------------------------

  private void tryCandidates() {
    while (!phase.terminal()) {
      Optional<Candidate> next = nextViable();
      if (next.isEmpty()) {
        exhausted();
        return;
      }
      choose(next.get());
      if (runSteps() instanceof Flow.Failover failover) {
        failOver(failover.reason());
      }
    }
  }

  private void exhausted() {
    String why = lastFailure == null ? "no_quote" : lastFailure;
    finish(
        new Failed("exhausted: " + why), "every provider has been tried; giving up (" + why + ")");
  }

  private Optional<Candidate> nextViable() {
    long now = services.clock().getAsLong();
    while (!candidates.isEmpty()) {
      Candidate c = candidates.poll();
      if (!c.quote().expired(now)) {
        return Optional.of(c);
      }
      note(
          "quote_expired",
          "skipping " + c.providerId() + ": its quote has expired",
          Map.of("provider", c.providerId()));
    }
    return Optional.empty();
  }

  private void choose(Candidate c) {
    current = c;
    done.clear();
    note(
        "provider_chosen",
        "going with %s (quote rate %s, fee %s)"
            .formatted(
                c.providerId(), Money.formatRate(c.quote().rate()), Money.format(c.quote().fee())),
        Map.of(
            "provider",
            c.providerId(),
            "remaining",
            candidates.stream().map(Candidate::providerId).toList()));
  }

  private void failOver(String reason) {
    note(
        "failover",
        "moving on from " + current.providerId() + ": " + reason,
        Map.of("provider", current.providerId()));
    current = null;
    done.clear();
    lastFailure = reason;
  }

  // ---- the steps ---------------------------------------------------------------------------

  private Flow runSteps() {
    for (Operation op : STEPS) {
      Flow flow = step(op);
      if (!(flow instanceof Flow.Next)) {
        return flow;
      }
    }
    return complete();
  }

  private Flow step(Operation op) {
    Reaction reaction = attemptStep(op, 1);
    while (reaction instanceof Reaction.Retry retry) {
      pause(retry.delayMs());
      reaction = attemptStep(op, retry.attempt());
    }
    return ((Reaction.Final) reaction).flow();
  }

  private Reaction attemptStep(Operation op, int attempt) {
    Optional<Flow> blocked = deadlineGuard(op);
    if (blocked.isPresent()) {
      return new Reaction.Final(blocked.get());
    }
    String key = key(op);
    note(
        "step_started",
        "asking %s to %s (attempt %d, key %s)"
            .formatted(current.providerId(), VERB.get(op), attempt, key),
        Map.of("provider", current.providerId(), "op", op.wire(), "key", key, "attempt", attempt));
    phase = PHASE.get(op);
    Optional<String> unavailable = services.barrier().get();
    if (unavailable.isPresent()) {
      return new Reaction.Final(auditUnavailable(unavailable.get()));
    }
    return react(op, call(op, key, attempt), attempt);
  }

  private Answer<Receipt> call(Operation op, String key, int attempt) {
    long started = System.nanoTime();
    Answer<Receipt> answer = server().invoke(op, a -> invokeStep(a, op, key), stepTimeout());
    journal("step", op, key, attempt, answer, started);
    return answer;
  }

  private Answer<Receipt> invokeStep(
      net.robbrown.actortx.provider.ProviderAdapter adapter, Operation op, String key) {
    return switch (op) {
      case ACCEPT_QUOTE -> adapter.acceptQuote(current.quote(), key);
      case AUTHORISE -> adapter.authorise(current.quote(), key);
      case PAY -> adapter.pay(current.quote(), key);
      default -> adapter.settle(key);
    };
  }

  private Reaction react(Operation op, Answer<Receipt> answer, int attempt) {
    return handlers.get(answer.tag()).handle(new Call(op, answer, attempt));
  }

  private void registerHandlers() {
    handlers.put("ok", this::stepSucceeded);
    handlers.put(
        "rejected",
        call ->
            new Reaction.Final(
                stepFailed(
                    call.op(),
                    "rejected: " + ((Answer.Rejected<Receipt>) call.answer()).reason())));
    handlers.put("error", this::stepErrored);
    handlers.put("timeout", this::inDoubt);
    handlers.put("in_doubt", this::inDoubt);
  }

  private Reaction stepSucceeded(Call call) {
    Operation op = call.op();
    note(
        "step_succeeded",
        current.providerId() + " did " + op.wire(),
        Map.of("provider", current.providerId(), "op", op.wire()));
    done.add(op);
    return new Reaction.Final(new Flow.Next());
  }

  private Reaction stepErrored(Call call) {
    Operation op = call.op();
    int attempt = call.attempt();
    String reason = ((Answer.Error<Receipt>) call.answer()).reason();
    if ("overloaded".equals(reason)) {
      note(
          "step_retry",
          "%s is busy (%s not sent); asking again shortly"
              .formatted(current.providerId(), op.wire()),
          Map.of("provider", current.providerId(), "op", op.wire()));
      return new Reaction.Retry(attempt, options.backoffMs());
    }
    if (attempt < options.maxAttempts()) {
      note(
          "step_retry",
          "%s errored on %s (%s); retrying (attempt %d/%d)"
              .formatted(
                  current.providerId(), op.wire(), reason, attempt + 1, options.maxAttempts()),
          Map.of("provider", current.providerId(), "op", op.wire()));
      return new Reaction.Retry(attempt + 1, options.backoffMs() * attempt);
    }
    return new Reaction.Final(stepFailed(op, "error: " + reason));
  }

  private Flow stepFailed(Operation op, String reason) {
    if (op == Operation.SETTLE) {
      // The payment already happened and cannot be undone: a person must settle it.
      return needsReview(
          "settlement_failed: " + reason,
          current.providerId() + " could not settle a payment that was already made: " + reason);
    }
    note(
        "step_failed",
        "%s could not %s: %s".formatted(current.providerId(), VERB.get(op), reason),
        Map.of("provider", current.providerId(), "op", op.wire(), "reason", reason));
    return compensate(undosOf(done), new After.FailOver(op.wire() + ": " + reason));
  }

  // ---- doubt ---------------------------------------------------------------------------------

  private Reaction inDoubt(Call call) {
    Operation op = call.op();
    Answer<Receipt> answer = call.answer();
    note(
        "in_doubt",
        "%s gave no definite answer to %s (%s); it may or may not have happened"
            .formatted(current.providerId(), op.wire(), answer.tag()),
        Map.of("provider", current.providerId(), "op", op.wire()));
    return new Reaction.Final(reconcile(op));
  }

  private Flow reconcile(Operation op) {
    phase = Phase.RECONCILING;
    for (int n = 1; ; n++) {
      Optional<Flow> flow = reconcileRound(op, n);
      if (flow.isPresent()) {
        return flow.get();
      }
      pause(options.reconcileIntervalMs());
    }
  }

  // One question to the provider; empty means still unresolved, ask again.
  private Optional<Flow> reconcileRound(Operation op, int n) {
    Answer<Receipt> status = status(op, n);
    if (status.isOk()) {
      note(
          "reconciled",
          current.providerId() + " confirms " + op.wire() + " did happen",
          Map.of("provider", current.providerId(), "op", op.wire()));
      done.add(op);
      return Optional.of(new Flow.Next());
    }
    if (!irreversible(op)) {
      return Optional.of(cancelByKey(op, status));
    }
    if (n >= options.reconcileAttempts()) {
      return Optional.of(
          needsReview(
              "in_doubt: " + op.wire(),
              op.wire()
                  + " with "
                  + current.providerId()
                  + " is unresolved; NOT failing over (the payment may already have happened)"));
    }
    note(
        "reconcile_pending",
        "%s cannot yet confirm %s (%s); asking again (%d/%d)"
            .formatted(
                current.providerId(), op.wire(), status.tag(), n, options.reconcileAttempts()),
        Map.of("provider", current.providerId(), "op", op.wire()));
    return Optional.empty();
  }

  private static boolean irreversible(Operation op) {
    return op == Operation.PAY || op == Operation.SETTLE;
  }

  private Answer<Receipt> status(Operation op, int n) {
    String key = key(op);
    note(
        "status_check",
        "asking " + current.providerId() + " what it holds for " + op.wire(),
        Map.of("provider", current.providerId(), "op", op.wire(), "key", key));
    phase = Phase.RECONCILING;
    long started = System.nanoTime();
    Answer<Receipt> answer =
        server().invoke(Operation.STATUS, a -> a.status(op, key), stepTimeout());
    journal("status", op, key, n, answer, started);
    return answer;
  }

  private Flow cancelByKey(Operation op, Answer<Receipt> unresolved) {
    note(
        "cancel_by_key",
        "%s cannot confirm %s (%s); cancelling by key to be safe"
            .formatted(current.providerId(), op.wire(), unresolved.tag()),
        Map.of("provider", current.providerId(), "op", op.wire()));
    List<Operation> pending = new ArrayList<>(done);
    pending.add(op);
    return compensate(undosOf(pending), new After.FailOver("in_doubt: " + op.wire()));
  }

  // ---- compensation ----------------------------------------------------------------------------

  private record Undo(Operation undo, Operation original) {}

  // The undos for what was done, latest first.
  private static List<Undo> undosOf(List<Operation> steps) {
    List<Undo> undos = new ArrayList<>();
    for (int i = steps.size() - 1; i >= 0; i--) {
      if (UNDO.containsKey(steps.get(i))) {
        undos.add(new Undo(UNDO.get(steps.get(i)), steps.get(i)));
      }
    }
    return undos;
  }

  private Flow compensate(List<Undo> undos, After after) {
    phase = Phase.COMPENSATING;
    for (Undo undo : undos) {
      Optional<Flow> stopped = runUndo(undo);
      if (stopped.isPresent()) {
        return stopped.get();
      }
    }
    return afterCompensation(after);
  }

  private Flow afterCompensation(After after) {
    return switch (after) {
      case After.FailOver failOver -> new Flow.Failover(failOver.reason());
      case After.Abort abort -> {
        finish(
            new Failed(abort.reason()),
            "stopping: " + abort.reason() + "; everything done so far has been undone");
        yield new Flow.Finished();
      }
    };
  }

  // Empty: the undo was done. Otherwise the transaction ended here.
  private Optional<Flow> runUndo(Undo undo) {
    for (int attempt = 1; ; attempt++) {
      Verdict verdict = undoAttempt(undo, attempt);
      if (!(verdict instanceof Verdict.Again)) {
        return verdict.flow();
      }
      pause(options.backoffMs() * attempt);
    }
  }

  private Verdict undoAttempt(Undo undo, int attempt) {
    String key = key(undo.original());
    note(
        "undo_started",
        "asking %s to %s (key %s)".formatted(current.providerId(), VERB.get(undo.undo()), key),
        Map.of("provider", current.providerId(), "op", undo.undo().wire(), "key", key));
    Optional<String> unavailable = services.barrier().get();
    if (unavailable.isPresent()) {
      return new Verdict.Stopped(auditUnavailable(unavailable.get()));
    }
    return undoVerdict(undo, sendUndo(undo, key, attempt), attempt);
  }

  private Answer<Receipt> sendUndo(Undo undo, String key, int attempt) {
    long started = System.nanoTime();
    Answer<Receipt> answer =
        server().invoke(undo.undo(), a -> invokeUndo(a, undo.undo(), key), stepTimeout());
    journal("undo", undo.undo(), key, attempt, answer, started);
    return answer;
  }

  private static Answer<Receipt> invokeUndo(
      net.robbrown.actortx.provider.ProviderAdapter adapter, Operation undo, String key) {
    return switch (undo) {
      case CANCEL_ACCEPT -> adapter.cancelAccept(key);
      default -> adapter.voidAuthorisation(key);
    };
  }

  /** How one attempt at an undo went. */
  private sealed interface Verdict {
    /** The flow that ended the transaction, if the undo did. */
    default Optional<Flow> flow() {
      return Optional.empty();
    }

    record Settled() implements Verdict {}

    record Again() implements Verdict {}

    record Stopped(Flow stoppedFlow) implements Verdict {
      @Override
      public Optional<Flow> flow() {
        return Optional.of(stoppedFlow);
      }
    }
  }

  private Verdict undoVerdict(Undo undo, Answer<Receipt> answer, int attempt) {
    String op = undo.undo().wire();
    if (answer.isOk()) {
      note(
          "undone",
          current.providerId() + " did " + op,
          Map.of("provider", current.providerId(), "op", op));
      return new Verdict.Settled();
    }
    if (answer instanceof Answer.Rejected<Receipt> rejected) {
      return stopped("rejected: " + rejected.reason(), op);
    }
    if (attempt < options.maxAttempts()) {
      note(
          "undo_retry",
          op + " at " + current.providerId() + " failed (" + answer.tag() + "); retrying",
          Map.of());
      return new Verdict.Again();
    }
    return stopped(answer.tag(), op);
  }

  private Verdict stopped(String why, String op) {
    return new Verdict.Stopped(
        needsReview(
            "compensation_failed: " + op + ": " + why,
            "could not " + op + " at " + current.providerId() + ": " + why));
  }

  // ---- guards
  // ------------------------------------------------------------------------------------

  private Optional<Flow> deadlineGuard(Operation op) {
    if (deadline == null || services.clock().getAsLong() < deadline.atMs()) {
      return Optional.empty();
    }
    if (op == Operation.SETTLE) {
      return Optional.of(
          needsReview(
              "settlement_overdue: " + deadline.region(),
              "the " + deadline.region() + " cut-off has passed before settlement was confirmed"));
    }
    note(
        "deadline_missed",
        "the " + deadline.region() + " cut-off has passed; stopping and undoing",
        Map.of("region", deadline.region()));
    return Optional.of(
        compensate(undosOf(done), new After.Abort("deadline_missed: " + deadline.region())));
  }

  // Write-ahead: the record of what we are about to ask must be durable before we ask.
  private Flow auditUnavailable(String why) {
    String message =
        "the durable log is unavailable (" + why + "); not moving money without a record";
    if (done.isEmpty() && phase != Phase.COMPENSATING) {
      finish(new Failed("audit_unavailable: " + why), message);
    } else {
      needsReview("audit_unavailable: " + why, message);
    }
    return new Flow.Finished();
  }

  // ---- the end ---------------------------------------------------------------------------------

  private Flow complete() {
    Candidate c = current;
    Optional<GuaranteeOutcome> guarantee = services.guarantees().settle(id(), request, c.net());
    Summary summary =
        new Summary(
            id(),
            c.providerId(),
            c.quote().rate(),
            c.quote().fee(),
            c.net(),
            done.stream().map(Operation::wire).toList(),
            policyLabel,
            guarantee.orElse(null));
    finish(
        new Completed(summary),
        "COMPLETED via %s: net %s at rate %s"
            .formatted(c.providerId(), Money.format(c.net()), Money.formatRate(c.quote().rate())));
    return new Flow.Finished();
  }

  private Flow needsReview(String reason, String message) {
    finish(new NeedsReview(reason), "NEEDS REVIEW: " + message);
    return new Flow.Finished();
  }

  private void finish(Result outcome, String message) {
    result = outcome;
    phase = outcome.phase();
    note(kindOf(outcome), message, Map.of("result", outcome.toString()));
    services.ended().accept(id());
    finished.complete(outcome);
  }

  private static String kindOf(Result outcome) {
    return switch (outcome) {
      case Completed completed -> "tx_completed";
      case Failed failed -> "tx_failed";
      case NeedsReview review -> "needs_review";
    };
  }

  // ---- helpers
  // -----------------------------------------------------------------------------------

  private ProviderServer server() {
    return (ProviderServer) providerOf(current.providerId()).source();
  }

  private Participant providerOf(String providerId) {
    return eligible.stream().filter(p -> p.id().equals(providerId)).findFirst().orElseThrow();
  }

  private Duration stepTimeout() {
    return Duration.ofMillis(options.stepTimeoutMs());
  }

  private String key(Operation op) {
    return id() + ":" + current.providerId() + ":" + KEY_STEP.get(op);
  }

  private void pause(long ms) {
    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(ms));
  }

  // The journal: the raw answer to one provider call, as the orchestrator saw it.
  private void journal(
      String kind, Operation op, String key, int attempt, Answer<?> answer, long startedNanos) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("provider", current.providerId());
    data.put("kind", kind);
    data.put("op", op.wire());
    data.put("key", key);
    data.put("attempt", attempt);
    data.put("class", answer.tag());
    data.put("latency_ms", (System.nanoTime() - startedNanos) / 1_000_000);
    services
        .events()
        .emit(
            id(),
            "journal",
            "provider_response",
            "journal: "
                + current.providerId()
                + " answered "
                + kind
                + " "
                + op.wire()
                + " ("
                + answer.tag()
                + ")",
            data);
  }

  private void note(String kind, String message, Map<String, Object> data) {
    Map<String, Object> withPhase = new LinkedHashMap<>(data);
    withPhase.put("phase", phase.wire());
    synchronized (history) {
      history.add(new HistoryEntry(services.clock().getAsLong(), phase, kind, message));
    }
    services.events().emit(id(), "payment", kind, message, withPhase);
  }
}

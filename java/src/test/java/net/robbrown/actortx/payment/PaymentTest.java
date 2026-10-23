package net.robbrown.actortx.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.robbrown.actortx.eligibility.Eligibility;
import net.robbrown.actortx.events.EventSink;
import net.robbrown.actortx.payment.Payment.Routing;
import net.robbrown.actortx.payment.Result.Completed;
import net.robbrown.actortx.payment.Result.Failed;
import net.robbrown.actortx.payment.Result.GuaranteeOutcome;
import net.robbrown.actortx.payment.Result.NeedsReview;
import net.robbrown.actortx.policy.Policy;
import net.robbrown.actortx.provider.FakeProvider;
import net.robbrown.actortx.provider.Mode;
import net.robbrown.actortx.provider.Operation;
import net.robbrown.actortx.provider.ProviderServer;
import net.robbrown.actortx.selection.Participant;
import net.robbrown.actortx.selection.QuoteSelector;
import net.robbrown.actortx.validation.Validated;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The payment workflow against scripted providers: the happy path, failover and every doubt. */
class PaymentTest {

  private static final Map<String, Object> RULES =
      Map.of(
          "default",
          Map.of(
              "instruments", List.of("spot", "forward"),
              "customer_types", List.of("business", "retail")),
          "regions",
          Map.of(
              "IN",
              Map.of(
                  "instruments", List.of("spot"),
                  "providers", Map.of("allow", List.of("northwind", "cobalt")),
                  "logs", List.of("IN"))));

  private final List<String> kinds = new CopyOnWriteArrayList<>();
  private final AtomicReference<Consumer<String>> hook = new AtomicReference<>(k -> {});
  private final AtomicLong skew = new AtomicLong();
  private final AtomicReference<Optional<String>> barrier = new AtomicReference<>(Optional.empty());
  private final EventSink sink =
      (tx, actor, kind, message, data) -> {
        kinds.add(kind);
        hook.get().accept(kind);
      };
  private final QuoteSelector selector = new QuoteSelector(sink);
  private FakeProvider north;
  private FakeProvider cobalt;
  private ProviderServer northServer;
  private ProviderServer cobaltServer;
  private PaymentOptions options;

  @BeforeEach
  void setUp() {
    north = new FakeProvider("northwind").rate("1.10");
    cobalt = new FakeProvider("cobalt").rate("1.00");
    northServer = new ProviderServer("northwind", north);
    cobaltServer = new ProviderServer("cobalt", cobalt);
    options =
        PaymentOptions.defaults()
            .withStepTimeoutMs(300)
            .withBackoffMs(1)
            .withReconcileIntervalMs(1);
  }

  @AfterEach
  void tearDown() {
    northServer.close();
    cobaltServer.close();
    selector.close();
  }

  private PaymentServices services() {
    return PaymentServices.of(selector, sink)
        .withBarrier(barrier::get)
        .withEnded(id -> kinds.add("ended"));
  }

  private PaymentServices clocked() {
    return new PaymentServices(
        selector,
        sink,
        barrier::get,
        () -> System.currentTimeMillis() + skew.get(),
        Deadlines.NONE,
        Guarantees.NONE,
        Workflows.STANDARD,
        id -> {});
  }

  private Routing routing(Eligibility eligibility, String label, Participant... who) {
    return new Routing(List.of(who), eligibility, Policy.defaults(), label);
  }

  private Routing both() {
    return routing(
        Eligibility.open(),
        "default",
        Participant.of("northwind", northServer),
        Participant.of("cobalt", cobaltServer));
  }

  private Result run(TransactionRequest request, Routing routing, PaymentServices services) {
    Payment payment = new Payment(request, routing, options, services).start();
    return payment.await(Duration.ofSeconds(10)).orElseThrow();
  }

  private Result run() {
    return run(TransactionRequest.of("tx-1", 100_000), both(), services());
  }

  private static String via(Result result) {
    return ((Completed) result).summary().provider();
  }

  @Test
  void aTransactionCompletesAtTheBestProvider() {
    Result result = run();

    assertThat(via(result)).isEqualTo("northwind");
    Result.Summary summary = ((Completed) result).summary();
    assertThat(summary.steps()).containsExactly("accept_quote", "authorise", "pay", "settle");
    assertThat(summary.policy()).isEqualTo("default");
    assertThat(north.effectCount(Operation.SETTLE)).isEqualTo(1);
    assertThat(kinds).contains("tx_received", "provider_chosen", "tx_completed", "ended");
  }

  @Test
  void aRejectedAcceptFailsOverWithNothingToUndo() {
    north.script(Operation.ACCEPT_QUOTE, Mode.REJECT);

    assertThat(via(run())).isEqualTo("cobalt");
    assertThat(kinds).contains("step_failed", "failover");
    assertThat(north.calls()).doesNotContainKey(Operation.CANCEL_ACCEPT);
  }

  @Test
  void aRejectedAuthoriseUndoesTheAcceptThenFailsOver() {
    north.script(Operation.AUTHORISE, Mode.REJECT);

    assertThat(via(run())).isEqualTo("cobalt");
    assertThat(north.effectCount(Operation.CANCEL_ACCEPT)).isEqualTo(1);
    assertThat(kinds).contains("undo_started", "undone");
  }

  @Test
  void aTechnicalErrorIsRetriedWithTheSameKey() {
    north.script(Operation.PAY, Mode.error("blip"), Mode.OK);

    assertThat(via(run())).isEqualTo("northwind");
    assertThat(north.calls().get(Operation.PAY)).isEqualTo(2);
    assertThat(north.effectCount(Operation.PAY)).isEqualTo(1);
    assertThat(kinds).contains("step_retry");
  }

  @Test
  void anErrorThatPersistsUndoesTheStepsAndFailsOver() {
    north.script(Operation.PAY, Mode.error("down"));

    assertThat(via(run())).isEqualTo("cobalt");
    assertThat(north.effectCount(Operation.VOID_AUTHORISATION)).isEqualTo(1);
    assertThat(north.effectCount(Operation.CANCEL_ACCEPT)).isEqualTo(1);
  }

  @Test
  void aBusyProviderIsAskedAgainWithoutUsingAnAttempt() {
    north.script(
        Operation.ACCEPT_QUOTE, Mode.error("overloaded"), Mode.error("overloaded"), Mode.OK);

    assertThat(via(run())).isEqualTo("northwind");
    assertThat(north.calls().get(Operation.ACCEPT_QUOTE)).isEqualTo(3);
  }

  @Test
  void aTimedOutAcceptIsCancelledByKeyAndFailsOver() {
    north.script(Operation.ACCEPT_QUOTE, Mode.TIMEOUT);

    assertThat(via(run())).isEqualTo("cobalt");
    assertThat(kinds).contains("in_doubt", "cancel_by_key");
  }

  @Test
  void aLostAnswerForPayIsReconciledNotRepeated() {
    north.script(Operation.PAY, Mode.IN_DOUBT);

    assertThat(via(run())).isEqualTo("northwind");
    assertThat(north.calls().get(Operation.PAY)).isEqualTo(1);
    assertThat(kinds).contains("reconciled");
  }

  @Test
  void reconciliationAsksAgainWhenTheProviderCannotSayYet() {
    north.script(Operation.PAY, Mode.IN_DOUBT);
    north.script(Operation.STATUS, Mode.UNKNOWN, Mode.OK);

    assertThat(via(run())).isEqualTo("northwind");
    assertThat(kinds).contains("reconcile_pending", "reconciled");
  }

  @Test
  void aPayThatCannotBeConfirmedGoesToReviewWithoutFailingOver() {
    north.script(Operation.PAY, Mode.TIMEOUT);

    Result result = run();

    assertThat(result).isEqualTo(new NeedsReview("in_doubt: pay"));
    assertThat(cobalt.calls()).doesNotContainKey(Operation.PAY);
  }

  @Test
  void aSettlementThatFailsGoesToReview() {
    north.script(Operation.SETTLE, Mode.REJECT);

    assertThat(run()).isEqualTo(new NeedsReview("settlement_failed: rejected: declined"));
  }

  @Test
  void aRefusedCompensationGoesToReview() {
    north.script(Operation.AUTHORISE, Mode.REJECT);
    north.script(Operation.CANCEL_ACCEPT, Mode.REJECT);

    assertThat(run())
        .isEqualTo(new NeedsReview("compensation_failed: cancel_accept: rejected: declined"));
  }

  @Test
  void aCompensationThatKeepsFailingGoesToReview() {
    north.script(Operation.AUTHORISE, Mode.REJECT);
    north.script(Operation.CANCEL_ACCEPT, Mode.error("down"));

    assertThat(run()).isEqualTo(new NeedsReview("compensation_failed: cancel_accept: error"));
  }

  @Test
  void aCompensationIsRetriedUntilItWorks() {
    north.script(Operation.AUTHORISE, Mode.REJECT);
    north.script(Operation.CANCEL_ACCEPT, Mode.error("blip"), Mode.OK);

    assertThat(via(run())).isEqualTo("cobalt");
    assertThat(kinds).contains("undo_retry");
  }

  @Test
  void withoutADurableLogNothingMovesAndTheTransactionFails() {
    barrier.set(Optional.of("disk_full"));

    assertThat(run()).isEqualTo(new Failed("audit_unavailable: disk_full"));
    assertThat(north.calls()).doesNotContainKey(Operation.ACCEPT_QUOTE);
  }

  @Test
  void aLogThatFailsAfterMoneyMovedGoesToReview() {
    hook.set(
        k -> {
          if ("step_succeeded".equals(k)) {
            barrier.set(Optional.of("gone"));
          }
        });

    assertThat(run()).isEqualTo(new NeedsReview("audit_unavailable: gone"));
  }

  @Test
  void aLogThatFailsDuringCompensationGoesToReview() {
    north.script(Operation.AUTHORISE, Mode.REJECT);
    hook.set(
        k -> {
          if ("undo_started".equals(k)) {
            barrier.set(Optional.of("gone"));
          }
        });
    // undo_started is announced before the barrier is checked

    assertThat(run()).isEqualTo(new NeedsReview("audit_unavailable: gone"));
  }

  @Test
  void aForbiddenTransactionIsRefusedBeforeAnyProviderIsAsked() {
    Eligibility rules = ((Validated.Valid<Eligibility>) Eligibility.fromMap(RULES)).value();
    TransactionRequest forward =
        TransactionRequest.builder("tx-2", 5_000).destination("IN").instrument("forward").build();

    Result result =
        run(forward, routing(rules, "", Participant.of("northwind", northServer)), services());

    assertThat(result).isInstanceOf(Failed.class);
    assertThat(((Failed) result).reason()).startsWith("not_permitted: forward is not permitted");
    assertThat(north.calls()).isEmpty();
  }

  @Test
  void aTransactionWithNoPermittedProviderIsRefused() {
    Eligibility rules = ((Validated.Valid<Eligibility>) Eligibility.fromMap(RULES)).value();
    TransactionRequest request =
        TransactionRequest.builder("tx-3", 5_000).destination("IN").build();

    Result result =
        run(request, routing(rules, "", Participant.of("zenith", cobaltServer)), services());

    assertThat(result).isEqualTo(new Failed("no_eligible_provider"));
  }

  @Test
  void jurisdictionNarrowsTheProvidersAndIsAnnounced() {
    Eligibility rules = ((Validated.Valid<Eligibility>) Eligibility.fromMap(RULES)).value();
    TransactionRequest request =
        TransactionRequest.builder("tx-4", 5_000).destination("IN").from("USD").to("INR").build();
    Routing routing =
        routing(
            rules,
            "",
            Participant.of("northwind", northServer),
            Participant.of("zenith", cobaltServer));

    assertThat(via(run(request, routing, services()))).isEqualTo("northwind");
    assertThat(kinds).contains("eligibility_decided");
    assertThat(kinds).doesNotContain("policy_resolved");
  }

  @Test
  void whenNoProviderQuotesTheTransactionFails() {
    north.script(Operation.REQUEST_QUOTE, Mode.REJECT);
    cobalt.script(Operation.REQUEST_QUOTE, Mode.REJECT);

    assertThat(run()).isEqualTo(new Failed("no_quote"));
  }

  @Test
  void aBaselineRouteIsNamedWhenNothingQuotes() {
    north.script(Operation.REQUEST_QUOTE, Mode.REJECT);
    cobalt.script(Operation.REQUEST_QUOTE, Mode.REJECT);
    options = options.withBaseline("cobalt");

    assertThat(run()).isEqualTo(new Failed("no_quote: fallback cobalt"));
  }

  @Test
  void whenEveryProviderFailsTheTransactionGivesUp() {
    north.script(Operation.ACCEPT_QUOTE, Mode.REJECT);
    cobalt.script(Operation.ACCEPT_QUOTE, Mode.REJECT);

    assertThat(run()).isEqualTo(new Failed("exhausted: accept_quote: rejected: declined"));
  }

  @Test
  void anExpiredQuoteIsSkipped() {
    north.quoteTtlMs(50);
    hook.set(
        k -> {
          if ("selection_made".equals(k)) {
            skew.set(200);
          }
        });

    assertThat(via(run(TransactionRequest.of("tx-5", 100_000), both(), clocked())))
        .isEqualTo("cobalt");
    assertThat(kinds).contains("quote_expired");
  }

  @Test
  void whenEveryQuoteHasExpiredNothingIsLeft() {
    hook.set(
        k -> {
          if ("selection_made".equals(k)) {
            skew.set(3_600_000);
          }
        });

    assertThat(run(TransactionRequest.of("tx-6", 100_000), both(), clocked()))
        .isEqualTo(new Failed("exhausted: no_quote"));
  }

  @Test
  void aCutOffAlreadyPassedStopsTheTransactionBeforeAnythingMoves() {
    PaymentServices services =
        services()
            .withDeadlines((request, now) -> Optional.of(new Deadlines.Deadline("EU", now - 1)));

    assertThat(run(TransactionRequest.of("tx-7", 100_000), both(), services))
        .isEqualTo(new Failed("deadline_missed: EU"));
    assertThat(kinds).contains("deadline_set", "deadline_missed");
  }

  @Test
  void aCutOffThatPassesMidwayUndoesWhatWasDone() {
    PaymentServices services =
        clocked()
            .withDeadlines(
                (request, now) -> Optional.of(new Deadlines.Deadline("EU", now + 60_000)));
    hook.set(
        k -> {
          if ("step_succeeded".equals(k)) {
            skew.set(120_000);
          }
        });

    assertThat(run(TransactionRequest.of("tx-8", 100_000), both(), services))
        .isEqualTo(new Failed("deadline_missed: EU"));
    assertThat(north.effectCount(Operation.CANCEL_ACCEPT)).isEqualTo(1);
  }

  @Test
  void aCutOffThatPassesBeforeSettlementGoesToReview() {
    PaymentServices services =
        clocked()
            .withDeadlines(
                (request, now) -> Optional.of(new Deadlines.Deadline("EU", now + 60_000)));
    AtomicLong paid = new AtomicLong();
    hook.set(
        k -> {
          if ("step_succeeded".equals(k) && paid.incrementAndGet() == 3) {
            skew.set(120_000);
          }
        });

    assertThat(run(TransactionRequest.of("tx-9", 100_000), both(), services))
        .isEqualTo(new NeedsReview("settlement_overdue: EU"));
  }

  @Test
  void aGuaranteeIsJudgedOnCompletion() {
    PaymentServices services =
        services()
            .withGuarantees(
                (id, request, net) -> Optional.of(new GuaranteeOutcome("m1", "2026-W42", -5)));

    Result result = run(TransactionRequest.of("tx-10", 100_000), both(), services);

    assertThat(((Completed) result).summary().guarantee())
        .isEqualTo(new GuaranteeOutcome("m1", "2026-W42", -5));
  }

  @Test
  void anUnexpectedErrorEndsInReviewNotSilence() {
    PaymentServices services =
        services()
            .withBarrier(
                () -> {
                  throw new IllegalStateException("boom");
                });

    Result result = run(TransactionRequest.of("tx-11", 100_000), both(), services);

    assertThat(result).isInstanceOf(NeedsReview.class);
    assertThat(((NeedsReview) result).reason()).startsWith("internal_error");
  }

  @Test
  void aSnapshotShowsHowFarTheTransactionHasGot() {
    Payment payment =
        new Payment(TransactionRequest.of("tx-12", 100_000), both(), options, services()).start();
    payment.await(Duration.ofSeconds(10));

    PaymentSnapshot snapshot = payment.snapshot();

    assertThat(payment.id()).isEqualTo("tx-12");
    assertThat(snapshot.phase()).isEqualTo(Phase.COMPLETED);
    assertThat(snapshot.history()).isNotEmpty();
    assertThat(snapshot.result()).isInstanceOf(Completed.class);
  }

  @Test
  void awaitingATransactionThatHasNotStartedTimesOut() {
    Payment payment = new Payment(TransactionRequest.of("tx-13", 1), both(), options, services());

    assertThat(payment.await(Duration.ofMillis(20))).isEmpty();
    assertThat(payment.snapshot().phase()).isEqualTo(Phase.RECEIVED);
  }

  @Test
  void awaitingIsInterruptible() throws InterruptedException {
    Payment payment = new Payment(TransactionRequest.of("tx-14", 1), both(), options, services());
    AtomicReference<Optional<Result>> seen = new AtomicReference<>();
    Thread waiter = new Thread(() -> seen.set(payment.await(Duration.ofSeconds(30))));
    waiter.start();
    waiter.interrupt();
    waiter.join(5_000);

    assertThat(seen.get()).isEmpty();
  }

  @Test
  void plainServicesNeedNoBarrierDeadlinesOrGuarantees() {
    PaymentServices plain = PaymentServices.of(selector, sink);

    assertThat(via(run(TransactionRequest.of("tx-15", 100_000), both(), plain)))
        .isEqualTo("northwind");
    plain.ended().accept("tx-15");
  }
}

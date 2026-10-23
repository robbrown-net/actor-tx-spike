package net.robbrown.actortx.review;

import static net.robbrown.actortx.review.ReviewFixtures.event;
import static net.robbrown.actortx.review.ReviewFixtures.needsReview;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.robbrown.actortx.payment.PaymentSnapshot;
import net.robbrown.actortx.provider.Operation;
import net.robbrown.actortx.review.ReviewEntry.Resolution;
import net.robbrown.actortx.review.ReviewEntry.Status;
import net.robbrown.actortx.service.InvalidInputException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReviewServiceTest {

  private static final String TX = "rv1";
  private static final long NOW = 1_000L;
  private static final PaymentSnapshot LIVE =
      ReviewFixtures.snapshot(
          TX, "best", List.of("accept_quote", "authorise", "pay"), List.of("best did pay"));

  private StubProviders providers;
  private RecordingSink events;
  private AtomicReference<Optional<String>> audit;
  private ReviewQueue queue;
  private ReviewService service;

  @BeforeEach
  void setUp() {
    providers =
        new StubProviders("best")
            .held(Operation.ACCEPT_QUOTE, TX + ":best:accept")
            .held(Operation.AUTHORISE, TX + ":best:authorise")
            .held(Operation.PAY, TX + ":best:pay");
    events = new RecordingSink();
    audit = new AtomicReference<>(Optional.empty());
    queue = new ReviewQueue(tx -> TX.equals(tx) ? Optional.of(LIVE) : Optional.empty());
    queue.onEvent(needsReview(TX, 4, "NEEDS REVIEW: could not settle"));
    service = new ReviewService(queue, providers, events, audit::get, () -> NOW);
  }

  private ReviewEntry entry() {
    return service.view(TX).orElseThrow();
  }

  @Test
  void checkingAsksEachStepAndRecordsItChangingNothing() {
    int before = providers.totalEffects();

    List<CheckAnswer> answers = service.check(TX, "robin");

    assertThat(answers)
        .extracting(CheckAnswer::op)
        .containsExactly("accept_quote", "authorise", "pay", "settle");
    assertThat(answers.get(0).key()).isEqualTo(TX + ":best:accept");
    assertThat(answers.get(2).answer()).isEqualTo("ok");
    assertThat(answers.get(3).answer()).isEqualTo("not_found");
    assertThat(providers.lookups()).isEqualTo(4);
    assertThat(providers.invoked()).isEmpty();
    assertThat(providers.totalEffects()).isEqualTo(before);
    assertThat(events.kinds()).containsExactly("review_checked");
    assertThat(events.emitted().get(0).actor()).isEqualTo("review");
    assertThat(events.emitted().get(0).data()).containsEntry("by", "robin");
    assertThat(entry().actions())
        .extracting(ReviewEntry.ReviewAction::kind)
        .containsExactly("check");
    assertThat(entry().actions().get(0).by()).isEqualTo("robin");
  }

  @Test
  void aSafeStepIsSentAgainUnderItsOriginalKeyAndTheIntentIsRecordedFirst() {
    RetryResult first = service.retry(TX, "settle", "robin");

    assertThat(first).isEqualTo(new RetryResult("settle", TX + ":best:settle", "ok"));
    assertThat(providers.effects(Operation.SETTLE)).isEqualTo(1);
    assertThat(events.kinds()).containsExactly("review_retry", "review_result");
    assertThat(events.emitted().get(1).data()).containsEntry("result", "ok");

    service.retry(TX, "settle", "robin");

    assertThat(providers.effects(Operation.SETTLE)).isEqualTo(1);
    assertThat(providers.invoked()).hasSize(2);
    assertThat(entry().actions())
        .extracting(ReviewEntry.ReviewAction::kind)
        .containsExactly("retry", "result", "retry", "result");
  }

  @Test
  void undoOperationsAreKeyedByTheStepTheyUndoAndOtherStepsCannotBeSentAgain() {
    RetryResult refund = service.retry(TX, "refund", "robin");

    assertThat(refund.key()).isEqualTo(TX + ":best:pay");
    assertThat(providers.effects(Operation.REFUND)).isEqualTo(1);
    assertThatThrownBy(() -> service.retry(TX, "pay", "robin"))
        .isInstanceOf(InvalidInputException.class)
        .hasMessageContaining("only these can be sent again");
    assertThat(providers.invoked()).containsExactly("refund|" + TX + ":best:pay");
  }

  @Test
  void resolvingNeedsAKnownOutcomeWhoIsDecidingAndWhyThenTheReviewIsClosed() {
    assertThatThrownBy(() -> service.resolve(TX, "maybe", "robin", "x"))
        .isInstanceOf(InvalidInputException.class)
        .hasMessage("choose one of: paid, not_paid, written_off");
    assertThatThrownBy(() -> service.resolve(TX, "paid", "", "x"))
        .hasMessage("say who is deciding");
    assertThatThrownBy(() -> service.resolve(TX, "paid", "robin", "  "))
        .hasMessageStartingWith("say why");
    assertThat(entry().status()).isEqualTo(Status.OPEN);
    assertThat(events.kinds()).isEmpty();

    Resolution resolution =
        service.resolve(TX, "paid", "robin", "confirmed with the provider's statement");

    assertThat(resolution)
        .isEqualTo(new Resolution("paid", "robin", "confirmed with the provider's statement", NOW));
    assertThat(events.kinds()).containsExactly("review_resolved");
    assertThat(events.emitted().get(0).data()).containsEntry("outcome", "paid");
    assertThat(entry().status()).isEqualTo(Status.RESOLVED);
    assertThat(entry().resolution().outcome()).isEqualTo("paid");
    assertThatThrownBy(() -> service.resolve(TX, "paid", "robin", "again"))
        .isInstanceOf(ReviewException.class)
        .hasMessage(TX + " has already been resolved");
    assertThatThrownBy(() -> service.check(TX, "robin")).isInstanceOf(ReviewException.class);
    assertThatThrownBy(() -> service.check("nope", "robin"))
        .isInstanceOf(ReviewException.class)
        .hasMessage("nope is not waiting for review");
  }

  @Test
  void ifTheAuditLogCannotTakeTheRecordNothingIsSentAndNothingChanges() {
    audit.set(Optional.of("sink_down: audit"));

    assertThatThrownBy(() -> service.retry(TX, "settle", "robin"))
        .isInstanceOf(ReviewException.class)
        .hasMessage("the audit log is unavailable, so nothing was done");
    assertThat(providers.invoked()).isEmpty();
    assertThatThrownBy(() -> service.check(TX, "robin")).isInstanceOf(ReviewException.class);
    assertThatThrownBy(() -> service.resolve(TX, "paid", "robin", "because"))
        .isInstanceOf(ReviewException.class);
    assertThat(entry().open()).isTrue();
    assertThat(entry().actions()).isEmpty();
  }

  @Test
  void everyActionNeedsANameAnOpenEntryAndAProviderThatIsThere() {
    assertThatThrownBy(() -> service.check(TX, ""))
        .isInstanceOf(InvalidInputException.class)
        .hasMessageStartingWith("say who is acting");
    assertThatThrownBy(() -> service.retry(TX, "settle", null))
        .hasMessageStartingWith("say who is acting");
    assertThatThrownBy(() -> service.check("nope", "robin"))
        .hasMessage("nope is not waiting for review");
  }

  @Test
  void aProviderThatIsGoneOrNeverChosenIsSaidPlainlyAndNothingWiredIsNoProblemToStart() {
    ReviewQueue bare = new ReviewQueue();
    bare.load(
        List.of(
            event(1, "t-gone", "payment", "tx_received", "in", Map.of("request", Map.of())),
            event(2, "t-gone", "payment", "provider_chosen", "go", Map.of("provider", "best")),
            needsReview("t-gone", 3, "needs review"),
            event(4, "t-none", "payment", "tx_received", "in", Map.of()),
            needsReview("t-none", 5, "needs review")));
    ReviewService none =
        new ReviewService(bare, new StubProviders(), events, Optional::empty, () -> NOW);

    assertThatThrownBy(() -> none.check("t-gone", "robin"))
        .isInstanceOf(ReviewException.class)
        .hasMessage("provider best is not available");
    assertThatThrownBy(() -> none.check("t-none", "robin"))
        .hasMessage("no provider was chosen for this transaction");
    assertThatThrownBy(() -> none.retry("t-none", "settle", "robin"))
        .hasMessage("no provider was chosen for this transaction");
  }

  @Test
  void dashboardCommandsAreRoutedAndABadOneIsExplained() {
    ReviewReply checked = service.command(Map.of("do", "check", "tx", TX, "by", "robin"));
    assertThat(checked).isInstanceOf(ReviewReply.Checked.class);
    assertThat(((ReviewReply.Checked) checked).answers()).hasSize(4);

    ReviewReply retried =
        service.command(Map.of("do", "retry", "tx", TX, "op", "refund", "by", "robin"));
    assertThat(((ReviewReply.Retried) retried).result().op()).isEqualTo("refund");

    ReviewReply resolved =
        service.command(
            Map.of(
                "do",
                "resolve",
                "tx",
                TX,
                "outcome",
                "not_paid",
                "by",
                "robin",
                "note",
                "refunded"));
    assertThat(((ReviewReply.Resolved) resolved).resolution().outcome()).isEqualTo("not_paid");

    String usage = "a review command needs do, tx and who is acting (check, retry, resolve)";
    assertThatThrownBy(() -> service.command(Map.of("do", "dance")))
        .isInstanceOf(InvalidInputException.class)
        .hasMessage(usage);
    assertThatThrownBy(() -> service.command(Map.of("do", "check", "tx", TX))).hasMessage(usage);
    assertThatThrownBy(() -> service.command(Map.of("tx", TX, "by", "robin"))).hasMessage(usage);
  }

  @Test
  void theQueueCanBeViewedListedAndCounted() {
    assertThat(service.view("nope")).isEmpty();
    assertThat(service.list()).extracting(ReviewEntry::tx).containsExactly(TX);
    assertThat(service.snapshot().open()).isEqualTo(1);
    assertThat(service.snapshot().resolved()).isZero();

    service.resolve(TX, "written_off", "robin", "exposure accepted");

    assertThat(service.snapshot().open()).isZero();
    assertThat(service.snapshot().resolved()).isEqualTo(1);
    assertThat(service.snapshot().entries()).hasSize(1);
  }
}

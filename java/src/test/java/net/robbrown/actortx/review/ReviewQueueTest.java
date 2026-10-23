package net.robbrown.actortx.review;

import static net.robbrown.actortx.review.ReviewFixtures.event;
import static net.robbrown.actortx.review.ReviewFixtures.needsReview;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.robbrown.actortx.events.Event;
import net.robbrown.actortx.payment.PaymentSnapshot;
import net.robbrown.actortx.review.ReviewEntry.Status;
import org.junit.jupiter.api.Test;

class ReviewQueueTest {

  private static final PaymentSnapshot LIVE =
      ReviewFixtures.snapshot(
          "rv1",
          "best",
          List.of("accept_quote", "authorise", "pay"),
          List.of("best did accept_quote", "best did authorise", "best did pay"));

  private static ReviewQueue queueWithLive() {
    return new ReviewQueue(tx -> "rv1".equals(tx) ? Optional.of(LIVE) : Optional.empty());
  }

  @Test
  void aNeedsReviewEventOpensAnEntryWithWhatIsKnownAboutIt() {
    ReviewQueue queue = queueWithLive();
    queue.onEvent(needsReview("rv1", 4, "NEEDS REVIEW: could not settle"));

    ReviewEntry entry = queue.find("rv1").orElseThrow();
    assertThat(entry.status()).isEqualTo(Status.OPEN);
    assertThat(entry.provider()).isEqualTo("best");
    assertThat(entry.done()).containsExactly("accept_quote", "authorise", "pay");
    assertThat(entry.reason()).contains("settle");
    assertThat(entry.story()).contains("best did pay");
    assertThat(entry.resolution()).isNull();
    assertThat(entry.actions()).isEmpty();
    assertThat(entry.openedAtMs()).isEqualTo(400);
    assertThat(queue.queue()).containsExactly(entry);
  }

  @Test
  void withoutItsProcessTheEntryHoldsOnlyTheReason() {
    ReviewQueue queue = new ReviewQueue();
    queue.onEvent(event(7, "ghost-1", "payment", "needs_review", "gone", Map.of()));

    ReviewEntry entry = queue.find("ghost-1").orElseThrow();
    assertThat(entry.provider()).isNull();
    assertThat(entry.done()).isEmpty();
    assertThat(entry.story()).isEmpty();
    assertThat(entry.reason()).isEqualTo("gone");
  }

  @Test
  void hearingAboutATransactionAgainChangesNothing() {
    ReviewQueue queue = new ReviewQueue();
    queue.onEvent(needsReview("ghost-1", 7, "gone"));
    queue.onEvent(needsReview("ghost-1", 9, "again"));

    assertThat(queue.queue()).hasSize(1);
    assertThat(queue.find("ghost-1").orElseThrow().reason()).isEqualTo("gone");
  }

  @Test
  void theBusNoiseIsIgnored() {
    ReviewQueue queue = new ReviewQueue();
    queue.onEvent(event(1, "rv2", "payment", "tx_received", "x", Map.of()));
    queue.onEvent(event(2, "rv2", "other", "needs_review", "x", Map.of()));
    queue.onEvent(event(3, null, "payment", "needs_review", "x", Map.of()));

    assertThat(queue.queue()).isEmpty();
    assertThat(queue.find("rv2")).isEmpty();
  }

  @Test
  void theQueueRebuildsFromTheLogAndWhatPeopleDidComesBack() {
    ReviewQueue queue = new ReviewQueue();
    queue.onEvent(needsReview("live", 0, "NEEDS REVIEW: live"));
    queue.load(sampleLog());

    ReviewEntry resolved = queue.find("t-old").orElseThrow();
    assertThat(resolved.status()).isEqualTo(Status.RESOLVED);
    assertThat(resolved.resolution().outcome()).isEqualTo("paid");
    assertThat(resolved.resolution().by()).isEqualTo("robin");
    assertThat(resolved.resolution().note()).isEqualTo("confirmed");
    assertThat(resolved.actions())
        .extracting(ReviewEntry.ReviewAction::kind)
        .containsExactly("check", "retry", "result");
    assertThat(resolved.done()).containsExactly("accept_quote");
    assertThat(resolved.request()).containsEntry("amount", 5_000);

    assertThat(queue.find("t-open").orElseThrow().open()).isTrue();
    assertThat(queue.find("live").orElseThrow().reason()).isEqualTo("NEEDS REVIEW: live");
    List<String> order = queue.queue().stream().map(ReviewEntry::tx).toList();
    assertThat(order.indexOf("t-open")).isLessThan(order.indexOf("t-old"));
  }

  @Test
  void theRebuiltStoryKeepsTheLastThirtyLinesAndLeavesOutTheSystemsOwn() {
    ReviewQueue queue = new ReviewQueue();
    List<Event> log = new java.util.ArrayList<>();
    log.add(event(1, "t-long", "journal", "provider_response", "journal line", Map.of()));
    log.add(event(2, "t-long", "review", "review_checked", "review line", Map.of("by", "x")));
    for (int i = 1; i <= 35; i++) {
      log.add(event(i + 2L, "t-long", "payment", "step", "m" + i, Map.of()));
    }
    log.add(needsReview("t-long", 40, "NEEDS REVIEW"));
    queue.load(log);

    ReviewEntry entry = queue.find("t-long").orElseThrow();
    assertThat(entry.story()).hasSize(30);
    assertThat(entry.story()).doesNotContain("journal line", "review line");
    assertThat(entry.story()).last().isEqualTo("NEEDS REVIEW");
  }

  @Test
  void aFailoverLeavesNoProviderAndNoSteps() {
    ReviewQueue queue = new ReviewQueue();
    queue.load(
        List.of(
            event(1, "t-f", "payment", "tx_received", "in", Map.of()),
            event(2, "t-f", "payment", "provider_chosen", "go", Map.of("provider", "best")),
            event(3, "t-f", "payment", "step_succeeded", "did", Map.of("op", "accept_quote")),
            event(4, "t-f", "payment", "failover", "moved on", Map.of()),
            needsReview("t-f", 5, "NEEDS REVIEW")));

    ReviewEntry entry = queue.find("t-f").orElseThrow();
    assertThat(entry.provider()).isNull();
    assertThat(entry.done()).isEmpty();
    assertThat(entry.request()).isEmpty();
  }

  @Test
  void aTransactionWithoutAReviewIsNotAnEntry() {
    ReviewQueue queue = new ReviewQueue();
    queue.load(List.of(event(1, "t-a", "payment", "tx_received", "in", Map.of())));

    assertThat(queue.queue()).isEmpty();
    assertThat(queue.find("t-a")).isEmpty();
  }

  @Test
  void resolvedEntriesComeLatestDecisionFirstAfterTheOpenOnes() {
    ReviewQueue queue = new ReviewQueue();
    queue.load(
        List.of(
            needsReview("t-1", 1, "a"),
            event(
                8,
                "t-1",
                "review",
                "review_resolved",
                "r",
                Map.of("by", "robin", "outcome", "paid", "note", "n")),
            needsReview("t-2", 9, "b"),
            event(
                20,
                "t-2",
                "review",
                "review_resolved",
                "r",
                Map.of("by", "robin", "outcome", "not_paid", "note", "n")),
            needsReview("t-3", 30, "c")));

    assertThat(queue.queue().stream().map(ReviewEntry::tx).toList())
        .containsExactly("t-3", "t-2", "t-1");
  }

  private static List<Event> sampleLog() {
    List<Event> head = new java.util.ArrayList<>(sampleLogHead());
    head.addAll(sampleLogReviews());
    return head;
  }

  private static List<Event> sampleLogHead() {
    return List.of(
        event(
            1, "t-old", "payment", "tx_received", "in", Map.of("request", Map.of("amount", 5_000))),
        event(
            2,
            "t-old",
            "payment",
            "provider_chosen",
            "go",
            Map.of("provider", "best", "remaining", List.of())),
        event(
            3,
            "t-old",
            "payment",
            "step_succeeded",
            "did",
            Map.of("provider", "best", "op", "accept_quote")),
        needsReview("t-old", 4, "NEEDS REVIEW: could not settle"),
        event(
            9, "t-open", "payment", "tx_received", "in", Map.of("request", Map.of("amount", 100))),
        needsReview("t-open", 10, "NEEDS REVIEW: x"),
        event(11, "live", "payment", "tx_received", "in", Map.of("request", Map.of("amount", 1))),
        needsReview("live", 12, "from the log"));
  }

  private static List<Event> sampleLogReviews() {
    return List.of(
        event(
            5,
            "t-old",
            "review",
            "review_checked",
            "r",
            Map.of("by", "robin", "answers", List.of())),
        event(6, "t-old", "review", "review_retry", "r", Map.of("by", "robin", "op", "settle")),
        event(
            7,
            "t-old",
            "review",
            "review_result",
            "r",
            Map.of("by", "robin", "op", "settle", "result", "ok")),
        event(
            8,
            "t-old",
            "review",
            "review_resolved",
            "r",
            Map.of("by", "robin", "outcome", "paid", "note", "confirmed")));
  }
}

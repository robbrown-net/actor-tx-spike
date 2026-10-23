package net.robbrown.actortx.review;

import static net.robbrown.actortx.review.ReviewFixtures.event;
import static net.robbrown.actortx.review.ReviewFixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import net.robbrown.actortx.review.ReviewEntry.ReviewAction;
import net.robbrown.actortx.review.ReviewEntry.Status;
import net.robbrown.actortx.service.InvalidInputException;
import org.junit.jupiter.api.Test;

class ReviewEntriesTest {

  private static final ReviewEntries.Known KNOWN =
      new ReviewEntries.Known("best", List.of("accept_quote"), Map.of("amount", 1), List.of("a"));

  @Test
  void theOutcomesAreTheThreeDecisionsInOrder() {
    assertThat(ReviewEntries.outcomes()).containsExactly("paid", "not_paid", "written_off");
  }

  @Test
  void aNewEntryIsOpenWithWhatIsKnown() {
    ReviewEntry entry = ReviewEntries.open("tx", 5, "NEEDS REVIEW: x", KNOWN);

    assertThat(entry.status()).isEqualTo(Status.OPEN);
    assertThat(entry.open()).isTrue();
    assertThat(entry.provider()).isEqualTo("best");
    assertThat(entry.done()).containsExactly("accept_quote");
    assertThat(entry.request()).containsEntry("amount", 1);
    assertThat(entry.story()).containsExactly("a");
    assertThat(entry.actions()).isEmpty();
    assertThat(entry.resolution()).isNull();
  }

  @Test
  void anActionIsAppendedAndADecisionClosesTheEntry() {
    ReviewEntry entry = ReviewEntries.open("tx", 5, "why", KNOWN);

    ReviewEntry acted = ReviewEntries.addAction(entry, 7, "check", "robin", null, "answers");
    assertThat(acted.actions())
        .containsExactly(new ReviewAction(7, "check", "robin", null, "answers"));
    assertThat(entry.actions()).isEmpty();

    ReviewEntry closed = ReviewEntries.resolve(acted, 9, "paid", "robin", "statement");
    assertThat(closed.open()).isFalse();
    assertThat(closed.resolution().outcome()).isEqualTo("paid");
    assertThat(closed.resolution().atMs()).isEqualTo(9);
    assertThat(closed.actions()).hasSize(1);
  }

  @Test
  void resolutionNeedsAKnownOutcomeWhoIsDecidingAndWhy() {
    assertThatThrownBy(() -> ReviewEntries.checkResolution("maybe", "robin", "x"))
        .isInstanceOf(InvalidInputException.class)
        .hasMessage("choose one of: paid, not_paid, written_off");
    assertThatThrownBy(() -> ReviewEntries.checkResolution(null, "robin", "x"))
        .hasMessage("choose one of: paid, not_paid, written_off");
    assertThatThrownBy(() -> ReviewEntries.checkResolution("paid", "", "x"))
        .hasMessage("say who is deciding");
    assertThatThrownBy(() -> ReviewEntries.checkResolution("paid", null, "why"))
        .hasMessage("say who is deciding");
    assertThatThrownBy(() -> ReviewEntries.checkResolution("paid", "robin", "  "))
        .hasMessageStartingWith("say why");
    assertThatThrownBy(() -> ReviewEntries.checkResolution("paid", "robin", null))
        .hasMessageStartingWith("say why");
    assertThatCode(() -> ReviewEntries.checkResolution("paid", "robin", "because"))
        .doesNotThrowAnyException();
  }

  @Test
  void onlyAPaymentsNeedsReviewAboutATransactionOpensAReview() {
    assertThat(ReviewEntries.opens(event(1, "tx", "payment", "needs_review", "m", Map.of())))
        .isTrue();
    assertThat(ReviewEntries.opens(event(1, "tx", "review", "needs_review", "m", Map.of())))
        .isFalse();
    assertThat(ReviewEntries.opens(event(1, "tx", "payment", "tx_received", "m", Map.of())))
        .isFalse();
    assertThat(ReviewEntries.opens(event(1, null, "payment", "needs_review", "m", Map.of())))
        .isFalse();
  }

  @Test
  void aKnownSnapshotKeepsTheLastThirtyStoryLines() {
    List<String> story =
        java.util.stream.IntStream.rangeClosed(1, 35).mapToObj(i -> "m" + i).toList();

    ReviewEntries.Known known = ReviewEntries.known(snapshot("tx", "best", List.of("pay"), story));

    assertThat(known.story()).hasSize(30).first().isEqualTo("m6");
    assertThat(known.story()).last().isEqualTo("m35");
    assertThat(known.provider()).isEqualTo("best");
    assertThat(known.done()).containsExactly("pay");
    assertThat(known.request()).isEmpty();
  }

  @Test
  void aShortStoryIsKeptWhole() {
    ReviewEntries.Known known =
        ReviewEntries.known(snapshot("tx", null, List.of(), List.of("one", "two")));

    assertThat(known.story()).containsExactly("one", "two");
  }

  @Test
  void aReviewRecordFoldsIntoTheEntryAndOtherRecordsDoNot() {
    ReviewEntry entry = ReviewEntries.open("tx", 5, "why", KNOWN);

    ReviewEntry same =
        ReviewEntries.apply(entry, event(1, "tx", "payment", "step_failed", "m", Map.of()));
    assertThat(same).isEqualTo(entry);

    ReviewEntry checked =
        ReviewEntries.apply(
            entry,
            event(
                2,
                "tx",
                "review",
                "review_checked",
                "m",
                Map.of("by", "robin", "answers", List.of())));
    assertThat(checked.actions()).hasSize(1);
    assertThat(checked.actions().get(0).kind()).isEqualTo("check");
  }

  @Test
  void aFieldIsTextOrNullWhenAbsent() {
    assertThat(ReviewEntries.text(Map.of("k", 42), "k")).isEqualTo("42");
    assertThat(ReviewEntries.text(Map.of(), "k")).isNull();
  }

  @Test
  void theKnownNothingIsEmpty() {
    assertThat(ReviewEntries.Known.NONE.provider()).isNull();
    assertThat(ReviewEntries.Known.NONE.done()).isEmpty();
    assertThat(ReviewEntries.Known.NONE.request()).isEmpty();
    assertThat(ReviewEntries.Known.NONE.story()).isEmpty();
  }
}

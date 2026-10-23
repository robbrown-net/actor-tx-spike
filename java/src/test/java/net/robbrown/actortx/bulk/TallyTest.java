package net.robbrown.actortx.bulk;

import static net.robbrown.actortx.bulk.PlanTest.params;
import static net.robbrown.actortx.bulk.PlanTest.valid;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class TallyTest {

  private final ManualClock clock = new ManualClock(1_000);

  private Tally tally(Map<String, Object> params) {
    return new Tally(clock, valid(params));
  }

  private static Map<String, Object> plan(Object... keysAndValues) {
    return PlanTest.params(keysAndValues);
  }

  private void accept(Tally tally, String id, CompletableFuture<Ending> ending) {
    tally.accepted(new Submission.Accepted(id, ending), BigDecimal.TEN);
  }

  @Test
  void endingsAreCountedWithTheirLatencyAndTheirReasons() {
    Tally tally = tally(plan("count", 4));
    CompletableFuture<Ending> first = new CompletableFuture<>();
    CompletableFuture<Ending> second = new CompletableFuture<>();
    accept(tally, "a", first);
    clock.advance(30);
    first.complete(new Ending(Ending.Status.COMPLETED, null));
    accept(tally, "b", second);
    clock.advance(70);
    second.complete(new Ending(Ending.Status.FAILED, "quote 12.5 expired"));

    Progress progress = tally.snapshot();

    assertThat(progress.completed()).isEqualTo(1);
    assertThat(progress.failed()).isEqualTo(1);
    assertThat(progress.inFlight()).isZero();
    assertThat(progress.latency()).isEqualTo(new Percentiles(30, 70, 70, 70));
    assertThat(progress.reasons()).containsExactly(new Progress.Reason("quote N expired", 1));
  }

  @Test
  void aPaymentThatNeedsReviewIsCountedWithItsReason() {
    Tally tally = tally(plan("count", 1));
    CompletableFuture<Ending> ending = new CompletableFuture<>();
    accept(tally, "a", ending);

    ending.complete(new Ending(Ending.Status.NEEDS_REVIEW, "stuck"));

    assertThat(tally.snapshot().needsReview()).isEqualTo(1);
    assertThat(tally.snapshot().reasons()).containsExactly(new Progress.Reason("stuck", 1));
  }

  @Test
  void aCompletedPaymentGivesNoReason() {
    Tally tally = tally(plan("count", 1));
    CompletableFuture<Ending> ending = new CompletableFuture<>();
    accept(tally, "a", ending);

    ending.complete(new Ending(Ending.Status.COMPLETED, null));

    assertThat(tally.snapshot().completed()).isEqualTo(1);
    assertThat(tally.snapshot().reasons()).isEmpty();
  }

  @Test
  void aFutureThatFailsIsCountedAsAFailureWithItsMessage() {
    Tally tally = tally(plan("count", 1));
    CompletableFuture<Ending> ending = new CompletableFuture<>();
    accept(tally, "a", ending);

    ending.completeExceptionally(new IllegalStateException("boom"));

    assertThat(tally.snapshot().failed()).isEqualTo(1);
    assertThat(tally.snapshot().reasons()).containsExactly(new Progress.Reason("boom", 1));
  }

  @Test
  void reasonsBeyondTheKindsKeptAreCountedAsOther() {
    Tally tally = tally(plan("count", 1));
    List<CompletableFuture<Ending>> endings = new ArrayList<>();
    for (int i = 0; i < 45; i++) {
      CompletableFuture<Ending> ending = new CompletableFuture<>();
      endings.add(ending);
      accept(tally, "t" + i, ending);
      ending.complete(new Ending(Ending.Status.FAILED, "fault-" + "x".repeat(i)));
    }

    List<Progress.Reason> reasons = tally.snapshot().reasons();

    assertThat(reasons).hasSize(8);
    assertThat(reasons.get(0)).isEqualTo(new Progress.Reason("other", 5));
  }

  @Test
  void aLongReasonIsCutShort() {
    Tally tally = tally(plan("count", 1));
    CompletableFuture<Ending> ending = new CompletableFuture<>();
    accept(tally, "a", ending);

    ending.complete(new Ending(Ending.Status.FAILED, "x".repeat(150)));

    assertThat(tally.snapshot().reasons().get(0).text()).hasSize(110);
  }

  @Test
  void theLatestAcceptedPaymentsAreKeptNewestFirstUpToTwelve() {
    Tally tally = tally(plan("count", 13));
    for (int i = 0; i < 13; i++) {
      accept(tally, "t" + i, new CompletableFuture<>());
    }

    List<Progress.Sent> recent = tally.snapshot().recent();

    assertThat(recent).hasSize(12);
    assertThat(recent.get(0).id()).isEqualTo("t12");
    assertThat(recent.get(11).id()).isEqualTo("t1");
  }

  @Test
  void theSenderIsAtItsCapOnlyWhenACapIsSetAndReached() {
    Tally uncapped = tally(plan("count", 4));
    Tally capped = tally(plan("count", 4, "max_in_flight", 1));

    accept(uncapped, "a", new CompletableFuture<>());
    accept(capped, "a", new CompletableFuture<>());

    assertThat(uncapped.atCap()).isFalse();
    assertThat(capped.atCap()).isTrue();
  }

  @Test
  void invalidBusyAndWaitedAreCounted() {
    Tally tally = tally(plan("count", 4));

    tally.invalid();
    tally.busy();
    tally.waited();

    Progress progress = tally.snapshot();
    assertThat(progress.submitted()).isEqualTo(1);
    assertThat(progress.errors()).isEqualTo(1);
    assertThat(progress.throttled()).isEqualTo(1);
    assertThat(progress.waited()).isEqualTo(1);
  }

  @Test
  void awaitingEndingsReturnsWhenEverythingHasEndedEvenIfSomeFailed() {
    Tally tally = tally(plan("count", 2));
    CompletableFuture<Ending> ok = new CompletableFuture<>();
    CompletableFuture<Ending> bad = new CompletableFuture<>();
    accept(tally, "a", ok);
    accept(tally, "b", bad);
    Thread.ofVirtual()
        .start(
            () -> {
              ok.complete(new Ending(Ending.Status.COMPLETED, null));
              bad.completeExceptionally(new IllegalStateException("gone"));
            });

    tally.awaitEndings();

    assertThat(tally.snapshot().inFlight()).isZero();
    assertThat(tally.snapshot().completed()).isEqualTo(1);
    assertThat(tally.snapshot().failed()).isEqualTo(1);
  }

  @Test
  void awaitingNoEndingsReturnsAtOnce() {
    tally(plan("count", 1)).awaitEndings();
  }

  @Test
  void stoppingAndFinishingAreExclusiveAndTheFirstWins() {
    Tally stopped = tally(plan("count", 5));

    assertThat(stopped.stop()).isTrue();
    assertThat(stopped.stop()).isFalse();
    assertThat(stopped.finish()).isFalse();
    assertThat(stopped.running()).isFalse();
    assertThat(stopped.snapshot().status()).isEqualTo(Progress.Status.STOPPED);

    Tally finished = tally(plan("count", 5));

    assertThat(finished.finish()).isTrue();
    assertThat(finished.stop()).isFalse();
    assertThat(finished.snapshot().status()).isEqualTo(Progress.Status.FINISHED);
  }

  @Test
  void aBatchWithEverythingSentButNotEndedIsDraining() {
    Tally tally = tally(plan("count", 2));
    tally.invalid();
    tally.invalid();

    assertThat(tally.snapshot().status()).isEqualTo(Progress.Status.DRAINING);
  }

  @Test
  void elapsedTimeAndRateRunUntilTheBatchEnds() {
    Tally tally = tally(plan("count", 10));
    tally.invalid();
    tally.invalid();
    tally.invalid();
    clock.advance(1_500);

    assertThat(tally.snapshot().elapsedMs()).isEqualTo(1_500);
    assertThat(tally.snapshot().perSecond()).isEqualTo(2.0);

    tally.finish();
    clock.advance(500);

    assertThat(tally.snapshot().elapsedMs()).isEqualTo(1_500);
  }

  @Test
  void aBatchThatEndsInstantlyHasItsRateOverOneMillisecond() {
    Tally tally = tally(plan("count", 10));
    tally.invalid();

    assertThat(tally.snapshot().perSecond()).isEqualTo(1_000.0);
  }
}

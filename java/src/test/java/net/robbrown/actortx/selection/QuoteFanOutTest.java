package net.robbrown.actortx.selection;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import net.robbrown.actortx.provider.Answer;
import net.robbrown.actortx.provider.Answer.Error;
import net.robbrown.actortx.provider.Answer.Timeout;
import net.robbrown.actortx.provider.HealthState;
import net.robbrown.actortx.provider.Quote;
import net.robbrown.actortx.provider.QuoteRequest;
import net.robbrown.actortx.provider.QuoteSource;
import net.robbrown.actortx.selection.QuoteFanOut.Target;
import org.junit.jupiter.api.Test;

/** The fan-out's own backstops, with sources that misbehave in ways a real server never would. */
class QuoteFanOutTest {

  private static final QuoteRequest REQUEST = QuoteRequest.of("r", 100L);

  private static Target target(String id, QuoteSource source) {
    return new Target(new Participant(id, source, null), HealthState.HEALTHY);
  }

  private static QuoteSource source(java.util.function.Supplier<Answer<Quote>> answer) {
    return new QuoteSource() {
      @Override
      public HealthState state() {
        return HealthState.HEALTHY;
      }

      @Override
      public Answer<Quote> requestQuote(QuoteRequest request, Duration timeout) {
        return answer.get();
      }
    };
  }

  @Test
  void aSourceThatIgnoresItsTimeoutIsCutOffAtTheDeadlinePlusGrace() {
    CountDownLatch never = new CountDownLatch(1);
    QuoteSource stuck =
        source(
            () -> {
              try {
                never.await();
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
              return new Timeout<>();
            });

    try (QuoteFanOut fanOut = new QuoteFanOut()) {
      List<Observation> seen = fanOut.observe(REQUEST, List.of(target("stuck", stuck)), 50);

      assertThat(seen).hasSize(1);
      assertThat(seen.get(0).result()).isEqualTo(new Timeout<Quote>());
      assertThat(seen.get(0).latencyMs()).isEqualTo(50L);
    }
    never.countDown();
  }

  @Test
  void aSourceThatThrowsCountsAsATimeout() {
    QuoteSource broken =
        source(
            () -> {
              throw new IllegalStateException("broken");
            });

    try (QuoteFanOut fanOut = new QuoteFanOut()) {
      List<Observation> seen = fanOut.observe(REQUEST, List.of(target("broken", broken)), 50);

      assertThat(seen.get(0).result()).isEqualTo(new Timeout<Quote>());
    }
  }

  @Test
  void aCallerInterruptedWhileWaitingStopsWaitingAndKeepsTheFlag() {
    CountDownLatch never = new CountDownLatch(1);
    QuoteSource stuck =
        source(
            () -> {
              try {
                never.await();
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
              return new Timeout<>();
            });

    try (QuoteFanOut fanOut = new QuoteFanOut()) {
      Thread.currentThread().interrupt();
      List<Observation> seen = fanOut.observe(REQUEST, List.of(target("stuck", stuck)), 5_000);

      assertThat(seen.get(0).result()).isEqualTo(new Timeout<Quote>());
      assertThat(Thread.interrupted()).isTrue();
    }
    never.countDown();
  }

  @Test
  void closingTheFanOutDuringABusyPauseStopsTheRetrying() throws InterruptedException {
    AtomicInteger asked = new AtomicInteger();
    QuoteSource busy =
        source(
            () -> {
              asked.incrementAndGet();
              return new Error<>("overloaded");
            });
    QuoteFanOut fanOut = new QuoteFanOut();
    Thread caller =
        Thread.ofVirtual()
            .start(() -> fanOut.observe(REQUEST, List.of(target("busy", busy)), 5_000));
    while (asked.get() < 2) {
      Thread.sleep(5);
    }

    fanOut.close();
    caller.join(2_000);

    assertThat(caller.isAlive()).isFalse();
    assertThat(asked.get()).isGreaterThanOrEqualTo(2);
  }
}

package net.robbrown.actortx.selection;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.robbrown.actortx.provider.Answer;
import net.robbrown.actortx.provider.Answer.Error;
import net.robbrown.actortx.provider.Answer.Timeout;
import net.robbrown.actortx.provider.HealthState;
import net.robbrown.actortx.provider.Quote;
import net.robbrown.actortx.provider.QuoteRequest;

/**
 * Asks providers for quotes concurrently within a deadline. Quoting has no side effects, so hedging
 * across providers is safe. The deadline is a hard bound: a late provider counts as a timeout.
 *
 * <p>A provider whose bulkhead is full has not been asked anything, so it is asked again every few
 * milliseconds until the deadline rather than given up on: under load a quote waits for a slot.
 */
final class QuoteFanOut implements AutoCloseable {

  /** A provider to ask, with the health state it was in when chosen. */
  record Target(Participant participant, HealthState state) {}

  private static final long BUSY_RETRY_MS = 15;
  private static final long GRACE_MS = 100;

  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  /** Ask every target; the answers come back in the order of the targets. */
  List<Observation> observe(QuoteRequest request, List<Target> targets, long deadlineMs) {
    long observedAt = System.currentTimeMillis();
    List<Future<Observation>> futures =
        targets.stream()
            .map(t -> executor.submit(() -> ask(t, request, deadlineMs, observedAt)))
            .toList();
    return java.util.stream.IntStream.range(0, targets.size())
        .mapToObj(i -> collect(futures.get(i), targets.get(i), deadlineMs, observedAt))
        .toList();
  }

  private Observation collect(
      Future<Observation> future, Target target, long deadlineMs, long observedAt) {
    try {
      return future.get(deadlineMs + GRACE_MS, TimeUnit.MILLISECONDS);
    } catch (TimeoutException | ExecutionException late) {
      future.cancel(true);
      return observation(target, new Timeout<>(), deadlineMs, observedAt);
    } catch (InterruptedException interrupted) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      return observation(target, new Timeout<>(), deadlineMs, observedAt);
    }
  }

  private Observation ask(Target target, QuoteRequest request, long deadlineMs, long observedAt) {
    long started = System.nanoTime();
    Answer<Quote> answer = askUntilServed(target.participant(), request, deadlineMs, started);
    return observation(target, answer, (System.nanoTime() - started) / 1_000_000, observedAt);
  }

  private Answer<Quote> askUntilServed(
      Participant participant, QuoteRequest request, long deadlineMs, long startedNanos) {
    Answer<Quote> answer = askOnce(participant, request, deadlineMs, startedNanos);
    while (retryable(answer, startedNanos, deadlineMs)) {
      pause();
      answer = askOnce(participant, request, deadlineMs, startedNanos);
    }
    return answer;
  }

  // Retry a busy provider while time remains and nobody has cancelled us.
  private static boolean retryable(Answer<Quote> answer, long startedNanos, long deadlineMs) {
    return busy(answer)
        && elapsedMs(startedNanos) + BUSY_RETRY_MS < deadlineMs
        && !Thread.currentThread().isInterrupted();
  }

  private Answer<Quote> askOnce(
      Participant participant, QuoteRequest request, long deadlineMs, long startedNanos) {
    long remaining = Math.max(deadlineMs - elapsedMs(startedNanos), 1);
    return participant.source().requestQuote(request, Duration.ofMillis(remaining));
  }

  private static boolean busy(Answer<Quote> answer) {
    return answer instanceof Error<Quote> error && "overloaded".equals(error.reason());
  }

  private static long elapsedMs(long startedNanos) {
    return (System.nanoTime() - startedNanos) / 1_000_000;
  }

  private static void pause() {
    try {
      Thread.sleep(BUSY_RETRY_MS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private static Observation observation(
      Target target, Answer<Quote> answer, long latencyMs, long observedAt) {
    return new Observation(
        target.participant().id(), target.state(), answer, latencyMs, observedAt);
  }

  @Override
  public void close() {
    executor.shutdownNow();
  }
}

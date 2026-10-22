package net.robbrown.actortx.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DispatcherTest {

  private final List<Dispatcher> dispatchers = new ArrayList<>();

  @AfterEach
  void closeDispatchers() {
    dispatchers.forEach(Dispatcher::close);
  }

  private Dispatcher open() {
    Dispatcher dispatcher = new Dispatcher();
    dispatchers.add(dispatcher);
    return dispatcher;
  }

  @Test
  void tasksRunInSubmissionOrderBeforeCloseReturns() {
    Dispatcher dispatcher = open();
    List<Integer> ran = new CopyOnWriteArrayList<>();
    for (int n = 1; n <= 3; n++) {
      int value = n;
      dispatcher.submit(() -> ran.add(value));
    }
    dispatcher.close();

    assertThat(ran).containsExactly(1, 2, 3);
    assertThat(dispatcher.thread().isAlive()).isFalse();
    assertThat(dispatcher.submit(() -> {})).isFalse();
  }

  @Test
  void closingTwiceIsSafe() {
    Dispatcher dispatcher = open();
    dispatcher.close();
    dispatcher.close();

    assertThat(dispatcher.thread().isAlive()).isFalse();
  }

  @Test
  void closingFromWithinATaskDoesNotWaitForItself() {
    Dispatcher dispatcher = open();
    CompletableFuture<Boolean> closedFromInside = new CompletableFuture<>();
    dispatcher.submit(
        () -> {
          dispatcher.close();
          closedFromInside.complete(true);
        });

    assertTimeoutPreemptively(Duration.ofSeconds(5), dispatcher::close);
    assertThat(closedFromInside.join()).isTrue();
  }

  @Test
  void closeWhileInterruptedRestoresTheFlagAndStillReturns() {
    Dispatcher dispatcher = open();
    Thread.currentThread().interrupt();
    dispatcher.close();

    assertThat(Thread.interrupted()).isTrue();
    dispatcher.close();
    assertThat(dispatcher.thread().isAlive()).isFalse();
  }

  @Test
  void interruptingTheDispatcherStopsItButStillRunsWhatIsPending() throws Exception {
    Dispatcher dispatcher = open();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    dispatcher.submit(
        () -> {
          entered.countDown();
          Gates.holdUntil(release);
        });
    entered.await();
    CompletableFuture<Boolean> pendingRan = new CompletableFuture<>();
    dispatcher.submit(() -> pendingRan.complete(true));
    await().until(() -> dispatcher.pending() == 1);

    dispatcher.thread().interrupt();
    release.countDown();

    assertThat(pendingRan.get(5, TimeUnit.SECONDS)).isTrue();
    await().until(() -> !dispatcher.thread().isAlive());
    assertThat(dispatcher.submit(() -> {})).isFalse();
  }
}

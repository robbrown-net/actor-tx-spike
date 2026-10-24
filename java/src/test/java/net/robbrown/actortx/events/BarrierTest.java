package net.robbrown.actortx.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The barrier as the dispatcher runs it: the flushes start there, and their waits do not. */
class BarrierTest {

  /** A sink whose flush finishes when its test says so. */
  private static final class DeferredSink implements DurableSink {

    final AtomicInteger started = new AtomicInteger();
    CompletableFuture<Optional<String>> outcome = new CompletableFuture<>();

    @Override
    public void write(Event event) {}

    @Override
    public Optional<String> flush() {
      return flushAsync().join();
    }

    @Override
    public CompletableFuture<Optional<String>> flushAsync() {
      started.incrementAndGet();
      return outcome;
    }
  }

  @Test
  void aPendingBarrierDoesNotHoldUpEventsEmittedAfterIt() {
    EventBus bus = new EventBus();
    DeferredSink sink = new DeferredSink();
    bus.registerSink("audit", sink);
    List<Long> seen = new CopyOnWriteArrayList<>();
    bus.subscribe(event -> seen.add(event.seq()));
    AtomicReference<Optional<String>> result = new AtomicReference<>();
    Thread.ofVirtual().start(() -> result.set(bus.barrier()));
    await().until(() -> sink.started.get() == 1);
    bus.emit("tx-1", "payment", "step_started", "m", Map.of());
    bus.emit("tx-1", "payment", "step_succeeded", "m", Map.of());
    await().until(() -> seen.size() >= 2);
    assertThat(result.get()).isNull();
    sink.outcome.complete(Optional.empty());
    await().until(() -> result.get() != null);
    assertThat(result.get()).isEmpty();
    bus.close();
  }

  @Test
  void aSinkWhoseAsyncFlushFailsIsReportedAndMarkedDown() {
    EventBus bus = new EventBus();
    DeferredSink sink = new DeferredSink();
    sink.outcome.completeExceptionally(new IllegalStateException("late"));
    bus.registerSink("audit", sink);
    assertThat(bus.barrier())
        .hasValueSatisfying(
            reason -> assertThat(reason).startsWith("sink_unresponsive: audit: ").contains("late"));
    assertThat(bus.barrier()).hasValue("sink_down: audit");
    bus.close();
  }

  @Test
  void aSinkWhoseFlushReportsAFailureReasonAsynchronouslyIsReturnedAsIs() {
    EventBus bus = new EventBus();
    DeferredSink sink = new DeferredSink();
    bus.registerSink("audit", sink);
    Thread.ofVirtual().start(() -> sink.outcome.complete(Optional.of("audit_unavailable: disk")));
    assertThat(bus.barrier()).hasValue("audit_unavailable: disk");
    bus.close();
  }

  @Test
  void aBarrierWithSeveralSinksWaitsForAllOfThemAndReportsTheFirstFailure() {
    EventBus bus = new EventBus();
    DeferredSink first = new DeferredSink();
    DeferredSink second = new DeferredSink();
    bus.registerSink("first", first);
    bus.registerSink("second", second);
    AtomicReference<Optional<String>> result = new AtomicReference<>();
    Thread.ofVirtual().start(() -> result.set(bus.barrier()));
    await().until(() -> first.started.get() == 1 && second.started.get() == 1);
    second.outcome.complete(Optional.of("second failed"));
    assertThat(result.get()).isNull();
    first.outcome.complete(Optional.of("first failed"));
    await().until(() -> result.get() != null);
    assertThat(result.get()).hasValue("first failed");
    bus.close();
  }
}

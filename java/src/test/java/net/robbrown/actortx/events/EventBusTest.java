package net.robbrown.actortx.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EventBusTest {

  private static final long NOW = 42L;

  private final List<EventBus> buses = new ArrayList<>();

  @AfterEach
  void closeBuses() {
    buses.forEach(EventBus::close);
  }

  private EventBus open() {
    return open(1_000);
  }

  private EventBus open(int capacity) {
    EventBus bus = new EventBus(capacity, () -> NOW);
    buses.add(bus);
    return bus;
  }

  private static void emit(EventBus bus, int n) {
    bus.emit("tx", "actor", "kind", "event " + n, Map.of("n", n));
  }

  private static List<Long> seqs(int from, int to) {
    return LongStream.rangeClosed(from, to).boxed().toList();
  }

  @Test
  void eventsAreSequencedTimestampedAndDeliveredToSubscribers() {
    EventBus bus = open();
    List<Event> received = new CopyOnWriteArrayList<>();
    bus.subscribe(received::add);
    emit(bus, 1);
    emit(bus, 2);
    await().untilAsserted(() -> assertThat(received).hasSize(2));

    Event first = received.get(0);
    assertThat(first.seq()).isEqualTo(1L);
    assertThat(first.message()).isEqualTo("event 1");
    assertThat(first.txId()).isEqualTo("tx");
    assertThat(first.data()).containsEntry("n", 1);
    assertThat(first.atMs()).isEqualTo(NOW);
    assertThat(received.get(1).seq()).isEqualTo(2L);
  }

  @Test
  void historyKeepsTheBufferedEventsOldestFirst() {
    EventBus bus = open();
    for (int n = 1; n <= 5; n++) {
      emit(bus, n);
    }

    assertThat(bus.history()).extracting(Event::seq).containsExactly(1L, 2L, 3L, 4L, 5L);
    assertThat(bus.history().subList(3, 5)).extracting(Event::seq).containsExactly(4L, 5L);
  }

  @Test
  void theBufferIsBoundedAndOldestEventsAreDropped() {
    EventBus bus = open(3);
    for (int n = 1; n <= 6; n++) {
      emit(bus, n);
    }

    assertThat(bus.history()).extracting(Event::seq).containsExactly(4L, 5L, 6L);
  }

  @Test
  void subscribingTwiceDoesNotDoubleDeliver() {
    EventBus bus = open();
    List<Event> received = new CopyOnWriteArrayList<>();
    Consumer<Event> listener = received::add;
    bus.subscribe(listener);
    bus.subscribe(listener);
    emit(bus, 1);

    assertThat(bus.barrier()).isEmpty();
    assertThat(received).extracting(Event::seq).containsExactly(1L);
  }

  @Test
  void aListenerThatThrowsIsDroppedAndNeverBreaksTheBus() {
    EventBus bus = open();
    AtomicInteger calls = new AtomicInteger();
    List<Event> healthy = new CopyOnWriteArrayList<>();
    bus.subscribe(
        event -> {
          calls.incrementAndGet();
          throw new IllegalStateException("bad listener");
        });
    bus.subscribe(healthy::add);
    emit(bus, 1);
    emit(bus, 2);

    assertThat(bus.barrier()).isEmpty();
    assertThat(calls).hasValue(1);
    assertThat(healthy).extracting(Event::seq).containsExactly(1L, 2L);
  }

  @Test
  void closingASubscriptionStopsItsDelivery() {
    EventBus bus = open();
    List<Event> received = new CopyOnWriteArrayList<>();
    Subscription subscription = bus.subscribe(received::add);
    emit(bus, 1);
    assertThat(bus.barrier()).isEmpty();

    subscription.close();
    emit(bus, 2);

    assertThat(bus.barrier()).isEmpty();
    assertThat(received).extracting(Event::seq).containsExactly(1L);
  }

  @Test
  void theGlobalApiEmitsWithAndWithoutDataAndTheHistoryReadsItBack() {
    EventBus bus = open();
    List<Event> received = new CopyOnWriteArrayList<>();
    bus.subscribe(received::add);
    bus.emit("global-1", "me", "ping", "no data", null);
    bus.emit("global-1", "me", "ping", "with data", Map.of("x", 1));

    assertThat(bus.barrier()).isEmpty();
    assertThat(received.get(0).txId()).isEqualTo("global-1");
    assertThat(received.get(0).message()).isEqualTo("no data");
    assertThat(received.get(0).data()).isEmpty();
    assertThat(received.get(1).data()).containsEntry("x", 1);
    assertThat(bus.history()).extracting(Event::txId).contains("global-1");
  }

  @Test
  void barrierWithNoSinksIsImmediate() {
    assertThat(open().barrier()).isEmpty();
  }

  @Test
  void barrierReturnsOnlyAfterEverySinkHasHandledEarlierEvents() {
    EventBus bus = open();
    FakeSink sink = FakeSink.ok();
    bus.registerSink("s", sink);
    for (int n = 1; n <= 20; n++) {
      emit(bus, n);
    }

    assertThat(bus.barrier()).isEmpty();
    assertThat(sink.seen()).isEqualTo(seqs(1, 20));
  }

  @Test
  void aSinkRegisteredLaterOnlyReceivesLaterEvents() {
    EventBus bus = open();
    emit(bus, 1);
    FakeSink sink = FakeSink.ok();
    bus.registerSink("late", sink);
    emit(bus, 2);

    assertThat(bus.barrier()).isEmpty();
    assertThat(sink.seen()).containsExactly(2L);
  }

  @Test
  void aSinkThatUnregistersStopsReceivingAndIsNoLongerWaitedFor() {
    EventBus bus = open();
    FakeSink sink = FakeSink.ok();
    bus.registerSink("gone", sink);
    emit(bus, 1);
    bus.unregisterSink("gone");
    emit(bus, 2);

    assertThat(bus.barrier()).isEmpty();
    assertThat(sink.seen()).containsExactly(1L);
  }

  @Test
  void aSinkThatDiesLeavesTheBarrierFailingUntilReplaced() {
    EventBus bus = open();
    bus.registerSink("audit", FakeSink.crashingOnWrite());
    emit(bus, 1);
    assertThat(bus.barrier()).contains("sink_down: audit");
    emit(bus, 2);

    FakeSink fresh = FakeSink.ok();
    bus.registerSink("audit", fresh);
    assertThat(bus.barrier()).isEmpty();
    emit(bus, 3);

    assertThat(bus.barrier()).isEmpty();
    assertThat(fresh.seen()).containsExactly(3L);
  }

  @Test
  void aRegisteredSinkThatCrashesWhileFlushingIsAFailure() {
    EventBus bus = open();
    bus.registerSink("flaky", FakeSink.crashingOnFlush());

    assertThat(bus.barrier().orElseThrow()).startsWith("sink_unresponsive: flaky: ");
    assertThat(bus.barrier()).contains("sink_down: flaky");
  }

  @Test
  void aSinksOwnFailureReasonIsReturnedByTheBarrier() {
    EventBus bus = open();
    bus.registerSink("disk", FakeSink.notDurable("disk full"));

    assertThat(bus.barrier()).contains("disk full");
  }

  @Test
  void aSinkThatUnregistersBeforeStoppingIsNotAFailure() {
    EventBus bus = open();
    bus.registerSink("gone", FakeSink.ok());
    bus.unregisterSink("gone");

    assertThat(bus.barrier()).isEmpty();
  }

  @Test
  void aSinkThatExitsAfterUnregisteringIsSkipped() {
    EventBus bus = open();
    bus.registerSink("leaving", FakeSink.crashingOnFlush());
    bus.unregisterSink("leaving");

    assertThat(bus.barrier()).isEmpty();
  }

  @Test
  void sinksReceiveEventsInEmitOrderUnderConcurrentEmitters() {
    EventBus bus = open();
    FakeSink sink = FakeSink.ok();
    bus.registerSink("ordered", sink);
    IntStream.rangeClosed(1, 1_000).parallel().forEach(n -> emit(bus, n));

    assertThat(bus.barrier()).isEmpty();
    assertThat(sink.seen()).isEqualTo(seqs(1, 1_000));
    assertThat(bus.history()).hasSize(1_000);
  }

  @Test
  void emitDoesNotBlockOnASlowSink() {
    EventBus bus = open();
    CountDownLatch gate = new CountDownLatch(1);
    FakeSink slow = FakeSink.gatedBy(gate);
    bus.registerSink("slow", slow);

    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> {
          emit(bus, 1);
          emit(bus, 2);
          emit(bus, 3);
        });
    gate.countDown();

    assertThat(bus.barrier()).isEmpty();
    assertThat(slow.seen()).isEqualTo(seqs(1, 3));
  }

  @Test
  void barrierReportsAClosedBus() {
    EventBus bus = open();
    bus.close();

    assertThat(bus.barrier()).contains("event_bus_closed");
  }

  @Test
  void closeIsIdempotentAndEmittingAfterCloseIsSafe() {
    EventBus bus = open();
    bus.close();
    bus.close();
    emit(bus, 1);

    assertThat(bus.history()).extracting(Event::seq).containsExactly(1L);
    assertThat(bus.barrier()).contains("event_bus_closed");
  }

  @Test
  void closingFromAListenerDoesNotHangTheBus() {
    EventBus bus = open();
    bus.subscribe(event -> bus.close());
    emit(bus, 1);

    assertTimeoutPreemptively(Duration.ofSeconds(5), bus::close);
    assertThat(bus.barrier()).contains("event_bus_closed");
  }

  @Test
  void theDefaultBusReadsTheSystemClock() {
    EventBus bus = new EventBus();
    buses.add(bus);
    long before = System.currentTimeMillis();
    emit(bus, 1);
    long after = System.currentTimeMillis();

    assertThat(bus.history().get(0).atMs()).isBetween(before, after);
  }

  @Test
  void aNonPositiveCapacityIsRejected() {
    assertThatThrownBy(() -> new EventBus(0, () -> NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("capacity");
    assertThatThrownBy(() -> new EventBus(-1, () -> NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

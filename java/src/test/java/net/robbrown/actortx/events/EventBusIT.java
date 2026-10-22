package net.robbrown.actortx.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/**
 * The bus under real load, through its public API only: many emitting threads, a listener, a sink
 * that fails and is replaced mid-stream, and a close that drains what was already emitted.
 */
class EventBusIT {

  private static final int EVENTS = 2_000;

  @Test
  void concurrentEmittersReachEverySinkInOrderAndSurviveASinkReplacement() {
    try (EventBus bus = new EventBus(50, () -> 7L)) {
      FakeSink primary = FakeSink.ok();
      List<Event> heard = new CopyOnWriteArrayList<>();
      bus.subscribe(heard::add);
      bus.registerSink("primary", primary);
      bus.registerSink("crashy", FakeSink.crashingOnWrite());

      IntStream.rangeClosed(1, EVENTS)
          .parallel()
          .forEach(n -> bus.emit("tx", "actor", "kind", "event " + n, Map.of()));
      assertThat(bus.barrier()).contains("sink_down: crashy");

      bus.registerSink("crashy", FakeSink.ok());
      bus.emit("tx", "actor", "kind", "after the replacement", Map.of());
      assertThat(bus.barrier()).isEmpty();

      assertThat(primary.seen()).isEqualTo(seqs(EVENTS + 1));
      assertThat(heard).hasSize(EVENTS + 1);
      assertThat(bus.history()).hasSize(50);

      bus.unregisterSink("primary");
      assertThat(bus.barrier()).isEmpty();
    }
  }

  @Test
  void closeDeliversWhatWasEmittedBeforeIt() {
    FakeSink sink = FakeSink.ok();
    EventBus bus = new EventBus();
    bus.registerSink("drained", sink);
    for (int n = 1; n <= 100; n++) {
      bus.emit("tx", "actor", "kind", "event " + n, Map.of());
    }
    bus.close();

    assertThat(sink.seen()).isEqualTo(seqs(100));
  }

  private static List<Long> seqs(int to) {
    return LongStream.rangeClosed(1, to).boxed().toList();
  }
}

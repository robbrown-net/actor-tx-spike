package net.robbrown.actortx.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EventTest {

  @Test
  void anEventCarriesWhatHappenedAndTheSinkSeamsWork() {
    Event event =
        new Event(1, 1_000, "tx-1", "payment", "step_started", "asking", Map.of("op", "pay"));

    assertThat(event.txId()).isEqualTo("tx-1");
    assertThat(event.data()).containsEntry("op", "pay");

    DurableSink sink =
        new DurableSink() {
          @Override
          public void write(Event e) {
            assertThat(e).isEqualTo(event);
          }

          @Override
          public Optional<String> flush() {
            return Optional.empty();
          }
        };
    sink.write(event);
    assertThat(sink.flush()).isEmpty();

    EventSink.NONE.emit("tx-1", "payment", "k", "m", Map.of());
  }
}

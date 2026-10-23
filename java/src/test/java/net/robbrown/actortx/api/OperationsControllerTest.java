package net.robbrown.actortx.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import net.robbrown.actortx.economics.EconomicsLedger;
import net.robbrown.actortx.economics.RateCard;
import net.robbrown.actortx.events.Event;
import net.robbrown.actortx.events.EventBus;
import net.robbrown.actortx.guarantee.GuaranteeLedger;
import net.robbrown.actortx.service.CutoffBook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class OperationsControllerTest {

  private EventBus bus;
  private CutoffBook cutoffs;
  private OperationsController controller;

  @BeforeEach
  void setUp() {
    bus = new EventBus();
    cutoffs = new CutoffBook();
    controller =
        new OperationsController(
            bus,
            new EconomicsLedger(RateCard.defaults()),
            new GuaranteeLedger(),
            cutoffs,
            new net.robbrown.actortx.service.ReplayService(
                java.util.Optional.empty(), java.util.Optional::empty));
  }

  @AfterEach
  void tearDown() {
    bus.close();
  }

  @Test
  void theMostRecentEventsAreReturnedOldestFirst() {
    for (int i = 0; i < 5; i++) {
      bus.emit("t", "payment", "k" + i, "m", Map.of());
    }

    List<Event> last = controller.events(2);

    assertThat(last).extracting(Event::kind).containsExactly("k3", "k4");
    assertThat(controller.events(100)).hasSize(5);
  }

  @Test
  void theLedgersAndCutoffsAreExposed() {
    cutoffs.set("IN", System.currentTimeMillis() + 60_000);

    assertThat(controller.economics().overall().transactions()).isZero();
    assertThat(controller.guarantees()).isEmpty();
    assertThat(controller.cutoffs()).containsKey("IN");
    assertThat(controller.replay()).containsEntry("available", false);
  }

  @Test
  void aStreamIsOpenedForEachClient() {
    SseEmitter emitter = controller.stream();

    assertThat(emitter).isNotNull();
    bus.emit("t", "payment", "k", "m", Map.of());
    bus.barrier();
    emitter.complete();
  }

  @Test
  void aClientThatHasGoneIsDroppedQuietly() throws IOException {
    SseEmitter emitter = controller.stream();
    emitter.complete();

    bus.emit("t", "payment", "late", "m", Map.of());
    bus.barrier();

    assertThat(bus.history()).extracting(Event::kind).contains("late");
  }
}

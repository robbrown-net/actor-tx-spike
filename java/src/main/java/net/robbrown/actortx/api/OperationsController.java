package net.robbrown.actortx.api;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import net.robbrown.actortx.economics.EconomicsLedger;
import net.robbrown.actortx.economics.Summary;
import net.robbrown.actortx.events.Event;
import net.robbrown.actortx.events.EventBus;
import net.robbrown.actortx.events.Subscription;
import net.robbrown.actortx.guarantee.GuaranteeLedger;
import net.robbrown.actortx.service.CutoffBook;
import net.robbrown.actortx.service.ReplayService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** What the system is doing and earning: the narrative, the economics, guarantees, cut-offs. */
@RestController
@RequestMapping("/api")
public class OperationsController {

  private final EventBus bus;
  private final EconomicsLedger economics;
  private final GuaranteeLedger guarantees;
  private final CutoffBook cutoffs;
  private final ReplayService replay;

  /** Fronts the operational views. */
  public OperationsController(
      EventBus bus,
      EconomicsLedger economics,
      GuaranteeLedger guarantees,
      CutoffBook cutoffs,
      ReplayService replay) {
    this.replay = replay;
    this.bus = bus;
    this.economics = economics;
    this.guarantees = guarantees;
    this.cutoffs = cutoffs;
  }

  /** The most recent events, oldest first. */
  @GetMapping("/events")
  public List<Event> events(@RequestParam(name = "limit", defaultValue = "200") int limit) {
    List<Event> all = bus.history();
    return all.subList(Math.max(0, all.size() - limit), all.size());
  }

  /** Every event as it happens (server-sent events). */
  @GetMapping("/stream")
  public SseEmitter stream() {
    SseEmitter emitter = new SseEmitter(0L);
    Subscription subscription = bus.subscribe(event -> send(emitter, event));
    emitter.onCompletion(subscription::close);
    emitter.onTimeout(subscription::close);
    return emitter;
  }

  private static void send(SseEmitter emitter, Event event) {
    try {
      emitter.send(SseEmitter.event().name(event.kind()).data(event));
    } catch (IOException | IllegalStateException gone) {
      emitter.complete();
    }
  }

  /** What the transactions cost and earned. */
  @GetMapping("/economics")
  public Summary economics() {
    return economics.summary();
  }

  /** How the standing guarantees have fared. */
  @GetMapping("/guarantees")
  public List<GuaranteeLedger.Row> guarantees() {
    return guarantees.all();
  }

  /** Milliseconds left before each region's cut-off. */
  @GetMapping("/cutoffs")
  public Map<String, Long> cutoffs() {
    return cutoffs.remaining(System.currentTimeMillis());
  }

  /**
   * Replay the durable log against the rules: did every transaction do what the system promises?
   */
  @GetMapping("/replay")
  public Map<String, Object> replay() {
    return replay.verify();
  }
}

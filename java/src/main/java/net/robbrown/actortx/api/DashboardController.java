package net.robbrown.actortx.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import net.robbrown.actortx.bulk.BulkRunner;
import net.robbrown.actortx.bulk.Progress;
import net.robbrown.actortx.events.Event;
import net.robbrown.actortx.events.EventBus;
import net.robbrown.actortx.events.Subscription;
import net.robbrown.actortx.metrics.Metrics;
import net.robbrown.actortx.metrics.Snapshot;
import net.robbrown.actortx.review.ReviewService;
import net.robbrown.actortx.review.ReviewSnapshot;
import net.robbrown.actortx.service.SnapshotService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** The existing single-page dashboard and the views it polls. */
@RestController
public class DashboardController {

  private final SnapshotService snapshots;
  private final Metrics metrics;
  private final ReviewService reviews;
  private final EventBus bus;
  private final BulkRunner bulk;

  /** Fronts the dashboard's data. */
  public DashboardController(
      SnapshotService snapshots,
      Metrics metrics,
      ReviewService reviews,
      EventBus bus,
      BulkRunner bulk) {
    this.bulk = bulk;
    this.snapshots = snapshots;
    this.metrics = metrics;
    this.reviews = reviews;
    this.bus = bus;
  }

  /** The page itself (the same {@code priv/panel.html} the Elixir side serves). */
  @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<byte[]> page() throws IOException {
    try (InputStream in = new ClassPathResource("static/panel.html").getInputStream()) {
      return ResponseEntity.ok().header("Cache-Control", "no-store").body(in.readAllBytes());
    }
  }

  /** Providers, policy, cut-offs, ledgers, admission. */
  @GetMapping("/api/snapshot")
  public Map<String, Object> snapshot() {
    return snapshots.snapshot();
  }

  /** Throughput, latency and JVM figures. */
  @GetMapping("/api/metrics")
  public Snapshot metrics() {
    return metrics.snapshot();
  }

  /** The manual-review queue. */
  @GetMapping("/api/review")
  public ReviewSnapshot review() {
    return reviews.snapshot();
  }

  /** Bulk sending: the batch in progress, or the last one. */
  @GetMapping("/api/bulk")
  public Progress bulk() {
    return bulk.snapshot();
  }

  /** The test lab: not offered in the Java port. */
  @GetMapping("/api/lab")
  public Map<String, Object> lab() {
    return Map.of("available", false);
  }

  /** Every event, past then live, as unnamed server-sent events (what the page listens for). */
  @GetMapping("/events")
  public SseEmitter events() {
    SseEmitter emitter = new SseEmitter(0L);
    bus.history().forEach(event -> push(emitter, event));
    Subscription subscription = bus.subscribe(event -> push(emitter, event));
    emitter.onCompletion(subscription::close);
    emitter.onTimeout(subscription::close);
    return emitter;
  }

  private static void push(SseEmitter emitter, Event event) {
    try {
      emitter.send(SseEmitter.event().id(Long.toString(event.seq())).data(view(event)));
    } catch (IOException | IllegalStateException gone) {
      emitter.complete();
    }
  }

  private static Map<String, Object> view(Event event) {
    return Map.of(
        "seq", event.seq(),
        "at", event.atMs(),
        "tx", event.txId() == null ? "" : event.txId(),
        "actor", event.actor(),
        "kind", event.kind(),
        "message", event.message(),
        "data", event.data());
  }
}

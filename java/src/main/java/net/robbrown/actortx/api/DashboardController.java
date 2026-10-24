package net.robbrown.actortx.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import net.robbrown.actortx.audit.AuditRouter;
import net.robbrown.actortx.audit.AuditStats;
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
import org.springframework.beans.factory.ObjectProvider;
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
  private final ObjectProvider<AuditRouter> audit;

  /** Fronts the dashboard's data. */
  public DashboardController(
      SnapshotService snapshots,
      Metrics metrics,
      ReviewService reviews,
      EventBus bus,
      BulkRunner bulk,
      ObjectProvider<AuditRouter> audit) {
    this.audit = audit;
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

  /**
   * Throughput, latency and JVM figures, plus the audit log's durability figures ({@code audit}, or
   * null when no audit directory is configured).
   */
  @GetMapping("/api/metrics")
  public Map<String, Object> metrics() {
    Snapshot snapshot = metrics.snapshot();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("raw", snapshot.raw());
    body.put("minutes", snapshot.minutes());
    body.put("latest", snapshot.latest());
    body.put("audit", audit.getIfAvailable() == null ? null : auditView(audit.getObject().stats()));
    return body;
  }

  private static Map<String, Object> auditView(AuditStats stats) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("records", stats.records());
    view.put("syncs", stats.syncs());
    view.put("synced_records", stats.syncedRecords());
    view.put("records_per_sync", stats.recordsPerSync());
    return view;
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

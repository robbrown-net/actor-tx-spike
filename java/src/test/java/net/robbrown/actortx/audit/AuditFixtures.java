package net.robbrown.actortx.audit;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.robbrown.actortx.events.Event;

/** Events and decisions for the audit tests, built the way the event bus builds them. */
final class AuditFixtures {

  private AuditFixtures() {}

  /** An event about {@code tx} with the default sentence and no data. */
  static Event event(long seq, String tx, String kind) {
    return event(seq, tx, kind, "something happened", Map.of());
  }

  /** An event about {@code tx}. */
  static Event event(long seq, String tx, String kind, String message, Map<String, Object> data) {
    return new Event(seq, 1_700_000_000_000L + seq, tx, "payment", kind, message, data);
  }

  /** A transaction's decision, naming the logs it must live in. */
  static Event received(long seq, String tx, String... logs) {
    return event(seq, tx, "tx_received", "m", decision(logs));
  }

  /** The data a decision carries: {@code eligibility.logs}. */
  static Map<String, Object> decision(String... logs) {
    Map<String, Object> eligibility = Map.of("logs", List.of(logs));
    return Map.of("eligibility", eligibility);
  }

  /** A forcer that counts the fsyncs it is asked for. */
  static AuditLog.Forcer counting(AtomicInteger forces) {
    return channel -> {
      forces.incrementAndGet();
      AuditLog.FSYNC.force(channel);
    };
  }

  /** A forcer that counts the attempts and always fails, as a disk that has gone would. */
  static AuditLog.Forcer failing(AtomicInteger attempts) {
    return channel -> {
      attempts.incrementAndGet();
      throw new IOException("disk gone");
    };
  }
}

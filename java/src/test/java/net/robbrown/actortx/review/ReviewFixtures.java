package net.robbrown.actortx.review;

import java.util.List;
import java.util.Map;
import net.robbrown.actortx.events.Event;
import net.robbrown.actortx.payment.PaymentSnapshot;
import net.robbrown.actortx.payment.PaymentSnapshot.HistoryEntry;
import net.robbrown.actortx.payment.Phase;

/** Builders for the events and snapshots the review tests feed the queue. */
final class ReviewFixtures {

  private ReviewFixtures() {}

  /** An event at sequence {@code n}, announced at {@code n * 100} milliseconds. */
  static Event event(
      long n, String tx, String actor, String kind, String message, Map<String, Object> data) {
    return new Event(n, n * 100, tx, actor, kind, message, data);
  }

  /** The payment process's {@code needs_review} for a transaction. */
  static Event needsReview(String tx, long n, String message) {
    return event(n, tx, "payment", "needs_review", message, Map.of());
  }

  /** A transaction as it stands, needing review, with the given story lines. */
  static PaymentSnapshot snapshot(
      String tx, String provider, List<String> done, List<String> story) {
    List<HistoryEntry> history =
        story.stream().map(m -> new HistoryEntry(1, Phase.NEEDS_REVIEW, "k", m)).toList();
    return new PaymentSnapshot(tx, Phase.NEEDS_REVIEW, provider, done, history, null);
  }
}

package net.robbrown.actortx.review;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import net.robbrown.actortx.events.Event;
import net.robbrown.actortx.payment.PaymentSnapshot;
import net.robbrown.actortx.review.ReviewEntries.Known;

/**
 * The review queue: one entry per transaction that ended in {@code needs_review}, open until a
 * person decides. Thread-safe and in memory.
 *
 * <p>It is fed by the event bus ({@link #onEvent}) and rebuilt from the audit log after a restart
 * ({@link #load}). A live entry is never overwritten by a rebuilt one. The queue only holds the
 * state; {@link ReviewService} is what people act through.
 */
public final class ReviewQueue {

  private final Function<String, Optional<PaymentSnapshot>> snapshots;
  private final Map<String, ReviewEntry> entries = new HashMap<>();

  /**
   * A queue that finds a live transaction's details through {@code snapshots}.
   *
   * @param snapshots the transaction as it stands, or empty when its process is gone
   */
  public ReviewQueue(Function<String, Optional<PaymentSnapshot>> snapshots) {
    this.snapshots = snapshots;
  }

  /** A queue that knows no live transactions: entries opened from events hold only the reason. */
  public ReviewQueue() {
    this(tx -> Optional.empty());
  }

  /**
   * Hear one event from the bus. A {@code needs_review} from the payment process opens an entry,
   * unless the transaction already has one; anything else is ignored.
   *
   * @param event the event
   */
  public void onEvent(Event event) {
    if (!ReviewEntries.opens(event) || contains(event.txId())) {
      return;
    }
    Known known =
        snapshots.apply(event.txId()).map(snap -> ReviewEntries.known(snap)).orElse(Known.NONE);
    add(ReviewEntries.open(event.txId(), event.atMs(), event.message(), known));
  }

  /**
   * Rebuild entries from audit-log records. Existing entries win.
   *
   * @param records the records, oldest first, as events
   */
  public void load(List<Event> records) {
    merge(ReviewEntries.rebuild(records));
  }

  /**
   * Every entry: the open ones first (oldest first), then the resolved ones (latest first).
   *
   * @return a snapshot of the queue
   */
  public synchronized List<ReviewEntry> queue() {
    List<ReviewEntry> open =
        entries.values().stream()
            .filter(ReviewEntry::open)
            .sorted(Comparator.comparingLong(ReviewEntry::openedAtMs))
            .toList();
    List<ReviewEntry> resolved =
        entries.values().stream()
            .filter(e -> !e.open())
            .sorted(Comparator.comparingLong((ReviewEntry e) -> e.resolution().atMs()).reversed())
            .toList();
    return Stream.concat(open.stream(), resolved.stream()).toList();
  }

  /**
   * The entry for a transaction, if it is in the queue.
   *
   * @param tx the transaction
   * @return the entry, or empty
   */
  public synchronized Optional<ReviewEntry> find(String tx) {
    return Optional.ofNullable(entries.get(tx));
  }

  /**
   * Replace an entry with its changed form (the same transaction).
   *
   * @param entry the changed entry
   */
  synchronized void replace(ReviewEntry entry) {
    entries.put(entry.tx(), entry);
  }

  private synchronized boolean contains(String tx) {
    return entries.containsKey(tx);
  }

  private synchronized void add(ReviewEntry entry) {
    entries.putIfAbsent(entry.tx(), entry);
  }

  private synchronized void merge(Map<String, ReviewEntry> loaded) {
    loaded.forEach((tx, entry) -> entries.putIfAbsent(tx, entry));
  }
}

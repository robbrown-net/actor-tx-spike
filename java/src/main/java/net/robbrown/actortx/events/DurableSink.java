package net.robbrown.actortx.events;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * A place events are made durable (the audit log). The event bus hands it every event in order;
 * {@link #flush()} is the write-ahead barrier: when it returns empty, everything written so far is
 * on disk, and money may move. If it reports a failure, nothing may move.
 */
public interface DurableSink {

  /**
   * Take an event for durable storage. Must not block for long; durability comes at {@link #flush}.
   */
  void write(Event event);

  /**
   * Make everything written so far durable, and wait for it.
   *
   * @return empty if it is, otherwise why it is not
   */
  Optional<String> flush();

  /**
   * Start making everything written so far durable, without waiting for it. The future completes
   * with the same outcome {@link #flush} would give. A sink that can batch its forces overrides
   * this, so the caller (the event bus dispatcher) is not held up by the disk. The default flushes
   * synchronously.
   *
   * @return the outcome, possibly still pending
   */
  default CompletableFuture<Optional<String>> flushAsync() {
    return CompletableFuture.completedFuture(flush());
  }
}

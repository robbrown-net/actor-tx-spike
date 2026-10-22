package net.robbrown.actortx.events;

import java.util.Optional;

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
   * Make everything written so far durable.
   *
   * @return empty if it is, otherwise why it is not
   */
  Optional<String> flush();
}

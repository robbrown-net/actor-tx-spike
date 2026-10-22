package net.robbrown.actortx.events;

import java.util.Map;

/**
 * Where the system announces what it is doing: the narrative. Components emit through this seam so
 * they stay testable without the event bus behind it.
 */
@FunctionalInterface
public interface EventSink {

  /** A sink that drops everything. */
  EventSink NONE = (txId, actor, kind, message, data) -> {};

  /**
   * Announce something.
   *
   * @param txId the transaction it is about, or {@code null} for the system
   * @param actor who is speaking ({@code "payment"}, {@code "provider:alpha"}...)
   * @param kind what happened ({@code "step_started"}...)
   * @param message the sentence a person reads
   * @param data structured detail for machines
   */
  void emit(String txId, String actor, String kind, String message, Map<String, Object> data);
}

package net.robbrown.actortx.review;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.robbrown.actortx.events.EventSink;

/** An event sink that keeps what it is told, in order. */
final class RecordingSink implements EventSink {

  /** One announcement. */
  record Emitted(
      String txId, String actor, String kind, String message, Map<String, Object> data) {}

  private final List<Emitted> emitted = new ArrayList<>();

  @Override
  public synchronized void emit(
      String txId, String actor, String kind, String message, Map<String, Object> data) {
    emitted.add(new Emitted(txId, actor, kind, message, data));
  }

  synchronized List<Emitted> emitted() {
    return List.copyOf(emitted);
  }

  synchronized List<String> kinds() {
    return emitted.stream().map(Emitted::kind).toList();
  }
}

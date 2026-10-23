package net.robbrown.actortx.bulk;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import net.robbrown.actortx.events.EventSink;

/** An event sink that keeps what it was told, in order. */
final class RecordingEvents implements EventSink {

  /** One announcement. */
  record Emitted(String kind, String message, Map<String, Object> data) {}

  private final List<Emitted> emitted = new CopyOnWriteArrayList<>();

  @Override
  public void emit(
      String txId, String actor, String kind, String message, Map<String, Object> data) {
    emitted.add(new Emitted(kind, message, data));
  }

  List<Emitted> all() {
    return List.copyOf(emitted);
  }

  List<String> kinds() {
    return emitted.stream().map(Emitted::kind).toList();
  }
}

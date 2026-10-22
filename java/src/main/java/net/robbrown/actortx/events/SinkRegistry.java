package net.robbrown.actortx.events;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The durable sinks, by name. Touched only from the dispatcher thread. A sink whose write or flush
 * throws is marked down and stays down until a sink is registered under its name (fail closed).
 */
final class SinkRegistry {

  private final Map<String, SinkEntry> sinks = new LinkedHashMap<>();

  void register(String name, DurableSink sink) {
    sinks.put(name, new SinkEntry(name, sink));
  }

  void unregister(String name) {
    sinks.remove(name);
  }

  void deliver(Event event) {
    sinks.values().forEach(entry -> entry.write(event));
  }

  Optional<String> barrier() {
    return firstDown().or(() -> flushAll());
  }

  private Optional<String> firstDown() {
    return sinks.values().stream()
        .filter(SinkEntry::isDown)
        .findFirst()
        .map(entry -> "sink_down: " + entry.name());
  }

  private Optional<String> flushAll() {
    return sinks.values().stream().map(SinkEntry::flush).flatMap(Optional::stream).findFirst();
  }

  /** One registered sink and whether it has failed. */
  private static final class SinkEntry {

    private final String name;
    private final DurableSink sink;
    private boolean down;

    SinkEntry(String name, DurableSink sink) {
      this.name = name;
      this.sink = sink;
    }

    String name() {
      return name;
    }

    boolean isDown() {
      return down;
    }

    void write(Event event) {
      if (down) {
        return;
      }
      try {
        sink.write(event);
      } catch (RuntimeException e) {
        down = true;
      }
    }

    Optional<String> flush() {
      try {
        return sink.flush();
      } catch (RuntimeException e) {
        down = true;
        return Optional.of("sink_unresponsive: " + name + ": " + e);
      }
    }
  }
}

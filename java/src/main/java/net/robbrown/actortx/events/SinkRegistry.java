package net.robbrown.actortx.events;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The durable sinks, by name. Writes and barriers are started from the dispatcher thread; a sink's
 * flush may finish on another thread, so the down flag is volatile. A sink whose write or flush
 * fails is marked down and stays down until a sink is registered under its name (fail closed).
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

  CompletableFuture<Optional<String>> barrier() {
    Optional<String> down = firstDown();
    if (down.isPresent()) {
      return CompletableFuture.completedFuture(down);
    }
    List<CompletableFuture<Optional<String>>> flushes =
        sinks.values().stream().map(SinkEntry::flushAsync).toList();
    return CompletableFuture.allOf(flushes.toArray(CompletableFuture[]::new))
        .thenApply(
            ignored ->
                flushes.stream()
                    .map(CompletableFuture::join)
                    .flatMap(Optional::stream)
                    .findFirst());
  }

  private Optional<String> firstDown() {
    return sinks.values().stream()
        .filter(SinkEntry::isDown)
        .findFirst()
        .map(entry -> "sink_down: " + entry.name());
  }

  /** One registered sink and whether it has failed. */
  private static final class SinkEntry {

    private final String name;
    private final DurableSink sink;
    private volatile boolean down;

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

    CompletableFuture<Optional<String>> flushAsync() {
      CompletableFuture<Optional<String>> started;
      try {
        started = sink.flushAsync();
      } catch (RuntimeException e) {
        return CompletableFuture.completedFuture(unresponsive(e));
      }
      return started.handle((outcome, error) -> error == null ? outcome : unresponsive(error));
    }

    private Optional<String> unresponsive(Throwable e) {
      down = true;
      return Optional.of("sink_unresponsive: " + name + ": " + e);
    }
  }
}

package net.robbrown.actortx.events;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The system's narrative: an ordered stream of {@link Event}s, kept in a bounded ring for late
 * joiners, pushed to subscribers, and written to durable sinks.
 *
 * <p>Emitting never blocks on a listener or a sink: events are numbered on the caller's thread and
 * then handed to one dispatcher thread, which delivers them in order. {@link #barrier()} is the
 * write-ahead point: it returns once every event emitted before the call is in every sink and the
 * sinks have flushed. Do not call {@link #barrier()} from a listener or sink: it would wait on the
 * dispatcher it is running on.
 */
public final class EventBus implements EventSink, AutoCloseable {

  private static final int DEFAULT_CAPACITY = 1_000;
  private static final String CLOSED = "event_bus_closed";

  private final Object lock = new Object();
  private final LongSupplier clock;
  private final EventRing ring;
  private final Subscribers subscribers = new Subscribers();
  private final SinkRegistry sinks = new SinkRegistry();
  private final Dispatcher dispatcher;
  private long seq;

  /**
   * A bus that keeps the last {@code capacity} events.
   *
   * @param capacity how many events the history keeps; at least 1
   * @param clock the wall clock, in milliseconds
   */
  public EventBus(int capacity, LongSupplier clock) {
    this.ring = new EventRing(capacity);
    this.clock = clock;
    this.dispatcher = new Dispatcher();
  }

  /** A bus that keeps the last 1,000 events and reads the system clock. */
  public EventBus() {
    this(DEFAULT_CAPACITY, System::currentTimeMillis);
  }

  @Override
  public void emit(
      String txId, String actor, String kind, String message, Map<String, Object> data) {
    synchronized (lock) {
      Event event = new Event(++seq, clock.getAsLong(), txId, actor, kind, message, copyOf(data));
      ring.add(event);
      dispatcher.submit(() -> deliver(event));
    }
  }

  /**
   * Subscribe a listener to every event emitted from now on. A listener that throws is dropped.
   *
   * @param listener receives each event on the dispatcher thread
   * @return closing it stops delivery
   */
  public Subscription subscribe(Consumer<Event> listener) {
    return subscribers.add(listener);
  }

  /**
   * The ring, oldest first.
   *
   * @return a copy of the buffered events
   */
  public List<Event> history() {
    synchronized (lock) {
      return ring.snapshot();
    }
  }

  /**
   * Register a durable sink. It receives every event from now on, in order. Registering again under
   * the same name replaces the sink and clears its failure.
   *
   * @param name the sink's name, used in failure reasons
   * @param sink the durable store
   */
  public void registerSink(String name, DurableSink sink) {
    dispatcher.submit(() -> sinks.register(name, sink));
  }

  /**
   * Remove a sink that is stopping on purpose. The barrier no longer waits for it.
   *
   * @param name the sink's name
   */
  public void unregisterSink(String name) {
    dispatcher.submit(() -> sinks.unregister(name));
  }

  /**
   * The write-ahead barrier: waits until every event emitted before this call is in every sink and
   * the sinks have made them durable. The dispatcher only starts the flushes, so a sink that forces
   * its writes (the audit log) does not hold up the events behind the barrier.
   *
   * @return empty if everything is durable; otherwise the reason, such as {@code "sink_down: name"}
   *     or the sink's own failure
   */
  public Optional<String> barrier() {
    CompletableFuture<Optional<String>> flushed = new CompletableFuture<>();
    if (!dispatcher.submit(() -> sinks.barrier().thenAccept(flushed::complete))) {
      return Optional.of(CLOSED);
    }
    return flushed.join();
  }

  /** Stop the dispatcher after the events already emitted have been delivered. Idempotent. */
  @Override
  public void close() {
    dispatcher.close();
  }

  private void deliver(Event event) {
    subscribers.deliver(event);
    sinks.deliver(event);
  }

  private static Map<String, Object> copyOf(Map<String, Object> data) {
    return data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
  }
}

package net.robbrown.actortx.events;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** The live listeners. A listener is kept once however often it subscribes. */
final class Subscribers {

  private final Set<Consumer<Event>> listeners = ConcurrentHashMap.newKeySet();

  Subscription add(Consumer<Event> listener) {
    listeners.add(listener);
    return () -> listeners.remove(listener);
  }

  void deliver(Event event) {
    for (Consumer<Event> listener : listeners) {
      deliverTo(listener, event);
    }
  }

  private void deliverTo(Consumer<Event> listener, Event event) {
    try {
      listener.accept(event);
    } catch (RuntimeException e) {
      listeners.remove(listener);
    }
  }
}

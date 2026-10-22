package net.robbrown.actortx.events;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** A bounded history: the newest events, oldest dropped first. Not thread-safe. */
final class EventRing {

  private final int capacity;
  private final Deque<Event> events = new ArrayDeque<>();

  EventRing(int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be at least 1: " + capacity);
    }
    this.capacity = capacity;
  }

  void add(Event event) {
    if (events.size() == capacity) {
      events.removeFirst();
    }
    events.addLast(event);
  }

  List<Event> snapshot() {
    return List.copyOf(events);
  }
}

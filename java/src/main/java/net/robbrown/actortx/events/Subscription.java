package net.robbrown.actortx.events;

/** A live subscription to the event bus. */
@FunctionalInterface
public interface Subscription extends AutoCloseable {

  /** Stop delivery to the listener. Closing twice is harmless. */
  @Override
  void close();
}

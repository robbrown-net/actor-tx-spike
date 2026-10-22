package net.robbrown.actortx.events;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * One thread that runs every task submitted to it, in submission order. Callers never wait for a
 * task unless they ask to; a slow task delays only the tasks behind it.
 */
final class Dispatcher {

  private static final Runnable STOP = () -> {};

  private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
  private final Thread thread;
  private boolean closed;

  Dispatcher() {
    this.thread = Thread.ofVirtual().name("event-bus").start(() -> drain());
  }

  /**
   * Queue a task.
   *
   * @return false if the dispatcher is closed and the task was refused
   */
  synchronized boolean submit(Runnable task) {
    if (closed) {
      return false;
    }
    queue.add(task);
    return true;
  }

  /** Run what is queued, then stop. Waits for the thread unless called from it. Idempotent. */
  void close() {
    stop();
    awaitExit();
  }

  Thread thread() {
    return thread;
  }

  int pending() {
    return queue.size();
  }

  private synchronized void stop() {
    if (!closed) {
      closed = true;
      queue.add(STOP);
    }
  }

  private void awaitExit() {
    if (Thread.currentThread() == thread) {
      return;
    }
    try {
      thread.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void drain() {
    Runnable task = take();
    while (task != STOP) {
      task.run();
      task = take();
    }
    refuseNewTasks();
    runLeftovers();
  }

  private Runnable take() {
    try {
      return queue.take();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return STOP;
    }
  }

  private synchronized void refuseNewTasks() {
    closed = true;
  }

  /** After an interrupt, tasks already queued still run, so no barrier waits forever. */
  private void runLeftovers() {
    for (Runnable task = queue.poll(); task != null; task = queue.poll()) {
      task.run();
    }
  }
}

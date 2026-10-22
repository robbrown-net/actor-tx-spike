package net.robbrown.actortx.events;

import java.util.concurrent.CountDownLatch;

/** Test helpers for holding a thread at a known point, deterministically. */
final class Gates {

  private Gates() {}

  /**
   * Block until the gate opens, ignoring interrupts on the way; the interrupt is restored after, so
   * the next blocking call on this thread sees it.
   */
  static void holdUntil(CountDownLatch gate) {
    boolean interrupted = false;
    while (gate.getCount() > 0) {
      try {
        gate.await();
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}

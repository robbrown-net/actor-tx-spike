package net.robbrown.actortx.bulk;

/**
 * Pauses the sender. Tests replace it with one that moves a fake clock, so nothing really waits.
 */
@FunctionalInterface
public interface Sleeper {

  /** Really sleeps the current thread. */
  Sleeper SYSTEM = millis -> Thread.sleep(millis);

  /**
   * Pause for {@code millis}.
   *
   * @param millis how long
   * @throws InterruptedException when the pause is cut short (the batch is being stopped)
   */
  void sleep(long millis) throws InterruptedException;
}

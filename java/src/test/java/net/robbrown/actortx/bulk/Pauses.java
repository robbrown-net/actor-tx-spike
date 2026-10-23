package net.robbrown.actortx.bulk;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A sleeper that records each pause and moves a {@link ManualClock} on, so nothing really waits.
 */
final class Pauses implements Sleeper {

  private final ManualClock clock;
  private final List<Long> pauses = new CopyOnWriteArrayList<>();

  Pauses(ManualClock clock) {
    this.clock = clock;
  }

  @Override
  public void sleep(long millis) {
    pauses.add(millis);
    clock.advance(millis);
    Thread.yield();
  }

  List<Long> pauses() {
    return List.copyOf(pauses);
  }
}

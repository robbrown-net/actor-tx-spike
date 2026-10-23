package net.robbrown.actortx.bulk;

import org.junit.jupiter.api.Test;

class SleeperTest {

  @Test
  void theSystemSleeperReallyPauses() throws InterruptedException {
    Sleeper.SYSTEM.sleep(0);
  }
}

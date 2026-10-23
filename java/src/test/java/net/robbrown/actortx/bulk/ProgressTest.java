package net.robbrown.actortx.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProgressTest {

  @Test
  void idleIsWhatTheDashboardShowsBeforeAnyBatch() {
    Progress idle = Progress.idle();

    assertThat(idle.status()).isEqualTo(Progress.Status.IDLE);
    assertThat(idle.plan()).isNull();
    assertThat(idle.count()).isZero();
    assertThat(idle.recent()).isEmpty();
    assertThat(idle.reasons()).isEmpty();
    assertThat(idle.latency()).isEqualTo(new Percentiles(0, 0, 0, 0));
  }

  @Test
  void ratesAreRoundedToOneDecimalPlace() {
    assertThat(Progress.round1(2.26)).isEqualTo(2.3);
    assertThat(Progress.round1(1.04)).isEqualTo(1.0);
  }
}

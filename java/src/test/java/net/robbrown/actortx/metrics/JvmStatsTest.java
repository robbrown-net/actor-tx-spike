package net.robbrown.actortx.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JvmStatsTest {

  @Test
  void currentReadsTheRunningJvm() {
    JvmStats now = JvmStats.current();

    assertThat(now.heapUsed()).isPositive();
    assertThat(now.heapMax()).isNotZero();
    assertThat(now.threads()).isPositive();
    assertThat(now.gcCount()).isNotNegative();
  }
}

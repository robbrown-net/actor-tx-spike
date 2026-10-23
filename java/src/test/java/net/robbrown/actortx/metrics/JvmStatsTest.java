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
    assertThat(now.nonHeapUsed()).isPositive();
    assertThat(now.gcTimeMs()).isNotNegative();
    assertThat(now.cpuLoad()).isBetween(0.0, 1.0);
  }

  @Test
  void theShortFormLeavesTheExtraReadingsAtZero() {
    JvmStats stats = new JvmStats(1, 2, 3, 4);

    assertThat(stats.nonHeapUsed()).isZero();
    assertThat(stats.cpuLoad()).isZero();
  }
}

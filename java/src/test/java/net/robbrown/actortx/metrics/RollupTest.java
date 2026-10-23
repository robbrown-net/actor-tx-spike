package net.robbrown.actortx.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RollupTest {

  private static Sample sample(long at, JvmStats jvm, long gc, TxCounts tx, double p95) {
    return new Sample(at, jvm, gc, tx, new Stats.Summary(0, p95, 0, 0, 0), Map.of(), 0);
  }

  private static TxCounts tx(long completed, long failed, long needsReview) {
    return new TxCounts(0, completed, failed, needsReview, 0, 0);
  }

  @Test
  void minuteFloorsToTheStartOfTheMinute() {
    assertThat(Rollup.minute(125_999)).isEqualTo(120_000);
    assertThat(Rollup.minute(120_000)).isEqualTo(120_000);
  }

  @Test
  void foldsSamplesIntoACompactBucket() {
    Rollup.Bucket bucket = Rollup.open(120_100);
    bucket.add(sample(120_100, new JvmStats(1_000, 9_000, 1, 0), 1, tx(1, 0, 0), 120));
    bucket.add(sample(121_100, new JvmStats(3_000, 9_000, 4, 0), 3, tx(2, 1, 1), 300));

    MinuteRollup done = bucket.finish();

    assertThat(bucket.at()).isEqualTo(120_000);
    assertThat(done.at()).isEqualTo(120_000);
    assertThat(done.samples()).isEqualTo(2);
    assertThat(done.heapUsedMean()).isEqualTo(2_000.0);
    assertThat(done.heapUsedMax()).isEqualTo(3_000.0);
    assertThat(done.threadsMax()).isEqualTo(4.0);
    assertThat(done.gcPerSecondMean()).isEqualTo(2.0);
    assertThat(done.completed()).isEqualTo(3);
    assertThat(done.failed()).isEqualTo(1);
    assertThat(done.needsReview()).isEqualTo(1);
    assertThat(done.e2eP95Max()).isEqualTo(300.0);
  }

  @Test
  void aBucketWithNoSamplesFinishesAsZeros() {
    MinuteRollup empty = Rollup.open(0).finish();

    assertThat(empty.samples()).isEqualTo(0);
    assertThat(empty.heapUsedMax()).isEqualTo(0.0);
    assertThat(empty.completed()).isEqualTo(0);
  }
}

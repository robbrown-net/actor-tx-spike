package net.robbrown.actortx.provider;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LimitsTest {

  @Test
  void quotesMayUseSixtyPercentStepsAllAndProtectiveCallsOneAndAHalfTimes() {
    Limits limits = Limits.defaults();

    assertThat(limits.limit(BulkheadClass.QUOTE)).isEqualTo(6);
    assertThat(limits.limit(BulkheadClass.STEP)).isEqualTo(10);
    assertThat(limits.limit(BulkheadClass.PROTECT)).isEqualTo(15);
  }

  @Test
  void theSharesRoundUpSoASingleSlotStillServesEveryone() {
    Limits one = Limits.defaults().withMaxInFlight(1);

    assertThat(one.limit(BulkheadClass.QUOTE)).isEqualTo(1);
    assertThat(one.limit(BulkheadClass.STEP)).isEqualTo(1);
    assertThat(one.limit(BulkheadClass.PROTECT)).isEqualTo(2);
  }

  @Test
  void theSharesCanBeChanged() {
    Limits limits = new Limits(10, 0.2, 0.0);

    assertThat(limits.limit(BulkheadClass.QUOTE)).isEqualTo(2);
    assertThat(limits.limit(BulkheadClass.PROTECT)).isEqualTo(10);
  }
}

package net.robbrown.actortx.economics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * The ledger as a whole: the default rate card, the system clock, a mixed run of outcomes across
 * providers, a bounded tail that overflows, a reset, and parallel recording.
 */
class EconomicsLedgerIT {

  @Test
  void aMixedRunOfOutcomesIsPricedAndTotalled() {
    EconomicsLedger ledger = new EconomicsLedger(RateCard.defaults());
    for (int i = 0; i < 300; i++) {
      ledger.record(outcome(i));
    }

    Summary summary = ledger.summary();
    Figures overall = summary.overall();

    assertThat(overall.transactions()).isEqualTo(300L);
    assertThat(overall.completed() + overall.failed() + overall.needsReview()).isEqualTo(300L);
    assertThat(summary.byProvider()).containsOnlyKeys("cobalt", "bluebird");
    assertThat(overall.netPicos())
        .isEqualTo(overall.benefitPicos() - overall.cost().total() - overall.platformPicos());
    assertThat(overall.platformPicos()).isNotNegative();
    assertThat(ledger.recent(10)).hasSize(10);
    assertThat(ledger.recent(1_000)).hasSize(200);
    assertThat(ledger.recent(1).get(0).txId()).isEqualTo("tx-299");
  }

  @Test
  void resetStartsAFreshRun() {
    EconomicsLedger ledger = new EconomicsLedger(RateCard.defaults());
    ledger.record(outcome(1));

    ledger.reset();

    assertThat(ledger.summary().overall().transactions()).isZero();
    assertThat(ledger.summary().byProvider()).isEmpty();
    assertThat(ledger.recent(5)).isEmpty();
  }

  @Test
  void parallelRecordingTotalsTheSameAsSerialRecording() {
    EconomicsLedger parallel = new EconomicsLedger(RateCard.defaults(), () -> 0L);
    EconomicsLedger serial = new EconomicsLedger(RateCard.defaults(), () -> 0L);
    IntStream.range(0, 2_000).parallel().forEach(i -> parallel.record(outcome(i)));
    IntStream.range(0, 2_000).forEach(i -> serial.record(outcome(i)));

    assertThat(parallel.summary().overall()).isEqualTo(serial.summary().overall());
    assertThat(parallel.summary().byProvider()).isEqualTo(serial.summary().byProvider());
  }

  /** A deterministic mix: every third has no provider, some fail, some need review. */
  private static Outcome outcome(int i) {
    Outcomes.Builder builder =
        Outcomes.completed("cobalt", 10_000L + (i % 7) * 100L)
            .txId("tx-" + i)
            .provider(providerOf(i))
            .margin((i % 5) * 10L - 20L);
    if (i % 11 == 0) {
      builder.failed().noDiscovery();
    } else if (i % 13 == 0) {
      builder.needsReview();
    }
    return builder.build();
  }

  private static String providerOf(int i) {
    if (i % 3 == 0) {
      return null;
    }
    return i % 2 == 0 ? "cobalt" : "bluebird";
  }
}

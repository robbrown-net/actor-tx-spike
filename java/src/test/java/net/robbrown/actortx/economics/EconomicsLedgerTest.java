package net.robbrown.actortx.economics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class EconomicsLedgerTest {

  private static final long FIXED_MS = 3_600_000L;
  private static final long PICOS = Costs.PICOS_PER_MINOR;

  /** A ledger whose clock never moves, so no platform time elapses. */
  private static EconomicsLedger fixedClock() {
    return new EconomicsLedger(RateCard.defaults(), () -> FIXED_MS);
  }

  @Test
  void completedTransactionsAccumulateSavingsMarginAndCost() {
    EconomicsLedger ledger = fixedClock();
    ledger.record(Outcomes.completed("cobalt", 11_000L).margin(500L).build());
    ledger.record(Outcomes.completed("cobalt", 10_500L).margin(-200L).build());

    Figures o = ledger.summary().overall();

    assertThat(o.transactions()).isEqualTo(2L);
    assertThat(o.completed()).isEqualTo(2L);
    assertThat(o.savedVsMedian()).isEqualTo(1_500L);
    assertThat(o.savedVsWorst()).isEqualTo(3_500L);
    assertThat(o.regretVsBest()).isEqualTo(500L);
    assertThat(o.margin()).isEqualTo(300L);
    assertThat(o.providerFees()).isEqualTo(300L);
    assertThat(o.cost().total()).isEqualTo(2 * (111_000L + 2_500_000L + 300_000_000L));
    assertThat(o.benefitPicos()).isEqualTo(1_800L * PICOS);
    assertThat(o.platformPicos()).isZero();
    assertThat(o.netPicos()).isEqualTo(o.benefitPicos() - o.cost().total() - o.platformPicos());
  }

  @Test
  void failedAndNeedsReviewTransactionsCostMoneyAndEarnNone() {
    EconomicsLedger ledger = fixedClock();
    ledger.record(Outcomes.completed("cobalt", 0L).failed().noDiscovery().build());
    ledger.record(Outcomes.completed("cobalt", 0L).needsReview().noDiscovery().build());

    Figures o = ledger.summary().overall();

    assertThat(o.failed()).isEqualTo(1L);
    assertThat(o.needsReview()).isEqualTo(1L);
    assertThat(o.completed()).isZero();
    assertThat(o.transactions()).isEqualTo(2L);
    assertThat(o.savedVsMedian()).isZero();
    assertThat(o.margin()).isZero();
    assertThat(o.providerFees()).isZero();
    assertThat(o.cost().total()).isPositive();
    assertThat(o.netPicos()).isEqualTo(-o.cost().total());
  }

  @Test
  void aCompletedTransactionWithoutPriceDiscoveryStillCountsItsMargin() {
    EconomicsLedger ledger = fixedClock();
    ledger.record(Outcomes.completed("cobalt", 0L).noDiscovery().margin(70L).build());

    Figures o = ledger.summary().overall();

    assertThat(o.margin()).isEqualTo(70L);
    assertThat(o.savedVsMedian()).isZero();
    assertThat(o.completed()).isEqualTo(1L);
  }

  @Test
  void totalsAreKeptPerProviderAndProviderlessOutcomesCountOnlyOverall() {
    EconomicsLedger ledger = fixedClock();
    ledger.record(Outcomes.completed("cobalt", 10_800L).txId("a").build());
    ledger.record(Outcomes.completed("bluebird", 10_100L).txId("b").build());
    ledger.record(
        Outcomes.completed("x", 0L).txId("c").failed().noDiscovery().provider(null).build());

    Summary summary = ledger.summary();

    assertThat(summary.byProvider()).containsOnlyKeys("cobalt", "bluebird");
    assertThat(summary.byProvider().get("cobalt").savedVsMedian()).isEqualTo(800L);
    assertThat(summary.byProvider().get("bluebird").savedVsMedian()).isEqualTo(100L);
    assertThat(summary.overall().transactions()).isEqualTo(3L);
  }

  @Test
  void platformCostAccruesWithElapsedTime() {
    AtomicLong clock = new AtomicLong(0L);
    EconomicsLedger ledger = new EconomicsLedger(RateCard.defaults(), clock::get);

    clock.set(FIXED_MS);

    Figures o = ledger.summary().overall();
    assertThat(o.platformPicos()).isEqualTo(83_200_000_000L);
    assertThat(o.netPicos()).isEqualTo(-83_200_000_000L);
  }

  @Test
  void theDefaultClockStartsCountingFromNow() {
    EconomicsLedger ledger = new EconomicsLedger(RateCard.defaults());

    Summary summary = ledger.summary();

    assertThat(summary.sinceMs()).isPositive();
    assertThat(summary.overall().platformPicos()).isNotNegative();
  }

  @Test
  void theRateCardIsVisibleInTheSummary() {
    assertThat(fixedClock().summary().rateCard().providerCallPicos()).isEqualTo(50_000_000L);
  }

  @Test
  void recentKeepsABoundedTailNewestLast() {
    EconomicsLedger ledger = fixedClock();
    for (int n = 1; n <= 205; n++) {
      ledger.record(Outcomes.completed("cobalt", 10_500L).txId("tx-" + n).build());
    }

    assertThat(ledger.summary().overall().transactions()).isEqualTo(205L);
    List<EconomicsLedger.Entry> all = ledger.recent(500);
    assertThat(all).hasSize(200);
    assertThat(all.get(all.size() - 1).txId()).isEqualTo("tx-205");
    assertThat(ledger.recent(2))
        .extracting(EconomicsLedger.Entry::txId)
        .containsExactly("tx-204", "tx-205");
  }

  @Test
  void recentEntriesCarryTheirCostAndValue() {
    EconomicsLedger ledger = fixedClock();
    ledger.record(Outcomes.completed("cobalt", 11_000L).txId("t").margin(500L).build());

    EconomicsLedger.Entry entry = ledger.recent(1).get(0);

    assertThat(entry.status()).isEqualTo(net.robbrown.actortx.payment.Outcome.COMPLETED);
    assertThat(entry.provider()).isEqualTo("cobalt");
    assertThat(entry.value().savedVsMedian()).isEqualTo(1_000L);
    assertThat(entry.cost().total()).isEqualTo(302_611_000L);
  }

  @Test
  void aCustomRateCardPricesTheSameUsageDifferently() {
    RateCard card = new RateCard(1_000L, 1_389_000L, 90_000_000_000L, 0L, 0L, 0L, 83_200_000_000L);
    EconomicsLedger ledger = new EconomicsLedger(card, () -> FIXED_MS);

    ledger.record(Outcomes.completed("cobalt", 10_000L).build());

    assertThat(ledger.summary().overall().cost().total()).isEqualTo(1_000L);
  }

  @Test
  void resetForgetsEverythingAndStartsCountingAgain() {
    AtomicLong clock = new AtomicLong(0L);
    EconomicsLedger ledger = new EconomicsLedger(RateCard.defaults(), clock::get);
    ledger.record(Outcomes.completed("cobalt", 10_500L).build());
    clock.set(FIXED_MS);

    ledger.reset();

    Summary summary = ledger.summary();
    assertThat(summary.overall().transactions()).isZero();
    assertThat(summary.byProvider()).isEmpty();
    assertThat(ledger.recent(10)).isEmpty();
    assertThat(summary.sinceMs()).isEqualTo(FIXED_MS);
    assertThat(summary.overall().platformPicos()).isZero();
  }

  @Test
  void concurrentRecordsAreAllCounted() {
    EconomicsLedger ledger = fixedClock();

    IntStream.range(0, 8_000)
        .parallel()
        .forEach(i -> ledger.record(Outcomes.completed("cobalt", 10_500L).build()));

    Summary summary = ledger.summary();
    assertThat(summary.overall().transactions()).isEqualTo(8_000L);
    assertThat(summary.byProvider().get("cobalt").transactions()).isEqualTo(8_000L);
    assertThat(ledger.recent(500)).hasSize(200);
  }
}

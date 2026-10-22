package net.robbrown.actortx.guarantee;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import net.robbrown.actortx.guarantee.GuaranteeLedger.Entry;
import net.robbrown.actortx.guarantee.GuaranteeLedger.Row;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GuaranteeLedgerTest {

  private GuaranteeLedger ledger;

  @BeforeEach
  void newLedger() {
    ledger = new GuaranteeLedger();
  }

  static Entry entry(String tx, String merchant, String period, long margin) {
    return new Entry(tx, merchant, period, 1000, 1000 + margin, margin);
  }

  @Test
  void summarisesMarginExposureBestAndWorst() {
    for (Entry e :
        List.of(
            entry("t1", "acme", "W42", 5),
            entry("t2", "acme", "W42", -3),
            entry("t3", "acme", "W42", 2),
            entry("t4", "acme", "W42", -1))) {
      ledger.record(e);
    }

    Row s = ledger.summary("acme", "W42");
    assertThat(s.count()).isEqualTo(4);
    assertThat(s.best()).isEqualTo(5);
    assertThat(s.worst()).isEqualTo(-3);
    assertThat(s.marginTotal()).isEqualTo(3);
    assertThat(s.exposureTotal()).isEqualTo(-4);
    assertThat(s.guaranteedTotal()).isEqualTo(4000);
    assertThat(s.achievedTotal()).isEqualTo(4003);
  }

  @Test
  void recordingIsIdempotentPerTransaction() {
    ledger.record(entry("t1", "acme", "W42", 5));
    ledger.record(entry("t1", "acme", "W42", 99));

    Row s = ledger.summary("acme", "W42");
    assertThat(s.count()).isEqualTo(1);
    assertThat(s.marginTotal()).isEqualTo(5);
  }

  @Test
  void emptySummariesAreZeros() {
    assertThat(ledger.summary("nobody", "W1"))
        .isEqualTo(new Row("nobody", "W1", 0, 0, 0, 0, 0, 0, 0));
  }

  @Test
  void allGroupsByMerchantAndPeriodAndSortsThem() {
    ledger.record(entry("a", "acme", "W42", 1));
    ledger.record(entry("b", "globex", "W42", -2));
    ledger.record(entry("c", "acme", "W43", 3));

    assertThat(ledger.all().stream().map(r -> r.merchantId() + "/" + r.period()).toList())
        .containsExactly("acme/W42", "acme/W43", "globex/W42");
  }

  @Test
  void aSummaryNeedsBothTheMerchantAndThePeriodToMatch() {
    ledger.record(entry("a", "acme", "W42", 1));
    ledger.record(entry("b", "globex", "W42", -2));
    ledger.record(entry("c", "acme", "W43", 3));

    assertThat(ledger.summary("acme", "W42").marginTotal()).isEqualTo(1);
    assertThat(ledger.summary("globex", "W42").marginTotal()).isEqualTo(-2);
    assertThat(ledger.summary("globex", "W43").count()).isZero();
  }

  @Test
  void aNewLedgerIsEmptyAndResetEmptiesIt() {
    assertThat(ledger.all()).isEmpty();
    assertThat(ledger.summary("nobody-global", "W0").count()).isZero();

    ledger.record(entry("a", "acme", "W42", 1));
    ledger.reset();
    assertThat(ledger.all()).isEmpty();
  }

  @Test
  void aGroupWithOnlyGainsHasNoExposure() {
    ledger.record(entry("a", "acme", "W42", 4));
    ledger.record(entry("b", "acme", "W42", 6));

    Row s = ledger.summary("acme", "W42");
    assertThat(s.exposureTotal()).isZero();
    assertThat(s.worst()).isEqualTo(4);
    assertThat(s.best()).isEqualTo(6);
  }

  @Test
  void concurrentRecordingLosesNothing() {
    IntStream.range(0, 1000)
        .parallel()
        .forEach(i -> ledger.record(entry("t" + i, "acme", "W42", 1)));

    Row s = ledger.summary("acme", "W42");
    assertThat(s.count()).isEqualTo(1000);
    assertThat(s.marginTotal()).isEqualTo(1000);
  }
}

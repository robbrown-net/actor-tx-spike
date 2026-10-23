package net.robbrown.actortx.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import net.robbrown.actortx.economics.EconomicsLedger;
import net.robbrown.actortx.economics.RateCard;
import net.robbrown.actortx.events.Event;
import org.junit.jupiter.api.Test;

class EconomicsRecorderTest {

  private final EconomicsLedger ledger = new EconomicsLedger(RateCard.defaults(), () -> 0L);
  private final EconomicsRecorder recorder = new EconomicsRecorder(ledger);
  private long seq;

  private void emit(String tx, long at, String kind, Map<String, Object> data) {
    recorder.accept(new Event(seq++, at, tx, "payment", kind, kind, data));
  }

  @Test
  void aTransactionIsPricedFromWhatItUsedWhenItEnds() {
    emit("t", 1_000, "tx_received", Map.of("request", Map.of("merchant_id", "acme")));
    emit("t", 1_010, "provider_chosen", Map.of("provider", "northwind"));
    emit("t", 1_020, "journal", Map.of());
    emit("t", 1_030, "step_succeeded", Map.of());
    emit("t", 1_035, "reconciled", Map.of());
    emit("t", 1_040, "undone", Map.of());
    emit("t", 1_050, "tx_completed", Map.of());

    var summary = ledger.summary();

    assertThat(summary.overall().completed()).isEqualTo(1);
    assertThat(summary.byProvider()).containsKey("northwind");
    assertThat(ledger.recent(1).get(0).provider()).isEqualTo("northwind");
    assertThat(ledger.recent(1).get(0).merchant()).isEqualTo("acme");
  }

  @Test
  void failuresAndReviewsAreCountedToo() {
    emit("a", 0, "tx_received", Map.of());
    emit("a", 5, "tx_failed", Map.of());
    emit("b", 0, "tx_received", Map.of("request", Map.of()));
    emit("b", 5, "needs_review", Map.of());

    assertThat(ledger.summary().overall().failed()).isEqualTo(1);
    assertThat(ledger.summary().overall().needsReview()).isEqualTo(1);
    assertThat(recorder.ledger()).isSameAs(ledger);
  }

  @Test
  void eventsForNobodyOrForUnknownTransactionsAreIgnored() {
    recorder.accept(new Event(0, 0, null, "operator", "cutoff_set", "m", Map.of()));
    emit("ghost", 0, "journal", Map.of());
    emit("ghost", 1, "tx_completed", Map.of());

    assertThat(ledger.summary().overall().transactions()).isZero();
  }
}

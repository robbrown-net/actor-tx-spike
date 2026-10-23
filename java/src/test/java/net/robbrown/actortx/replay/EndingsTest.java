package net.robbrown.actortx.replay;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import net.robbrown.actortx.payment.Outcome;
import org.junit.jupiter.api.Test;

/** How a transaction ended, from its terminal record: no terminal record means still open. */
class EndingsTest {

  @Test
  void theTerminalRecordKindsGiveTheOutcomes() {
    assertThat(Tx.outcomeOf("tx_completed")).contains(Outcome.COMPLETED);
    assertThat(Tx.outcomeOf("tx_failed")).contains(Outcome.FAILED);
    assertThat(Tx.outcomeOf("needs_review")).contains(Outcome.NEEDS_REVIEW);
    assertThat(Tx.outcomeOf(null)).isEmpty();
    assertThat(Tx.outcomeOf("something_else")).isEmpty();
  }

  @Test
  void onlyTheTerminalKindsEndATransaction() {
    assertThat(Tx.terminalKinds()).containsExactly("tx_completed", "tx_failed", "needs_review");
    assertThat(Tx.outcomeOf("step_succeeded")).isEmpty();
  }

  @Test
  void anOpenTransactionReadsAsOpenAndOutcomesReadByTheirWireName() {
    assertThat(Tx.label(Optional.empty())).isEqualTo("open");
    assertThat(Tx.label(Optional.of(Outcome.NEEDS_REVIEW))).isEqualTo("needs_review");
  }
}

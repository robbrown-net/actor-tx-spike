package net.robbrown.actortx.replay;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** How a transaction ended, from its terminal record. */
class OutcomeTest {

  @Test
  void theTerminalRecordKindsGiveTheOutcomes() {
    assertThat(Outcome.forTerminal("tx_completed")).isEqualTo(Outcome.COMPLETED);
    assertThat(Outcome.forTerminal("tx_failed")).isEqualTo(Outcome.FAILED);
    assertThat(Outcome.forTerminal("needs_review")).isEqualTo(Outcome.NEEDS_REVIEW);
    assertThat(Outcome.forTerminal(null)).isEqualTo(Outcome.OPEN);
    assertThat(Outcome.forTerminal("something_else")).isEqualTo(Outcome.OPEN);
  }

  @Test
  void onlyTheTerminalKindsEndATransaction() {
    assertThat(Outcome.terminalKinds())
        .containsExactly("tx_completed", "tx_failed", "needs_review");
    assertThat(Outcome.isTerminal("tx_failed")).isTrue();
    assertThat(Outcome.isTerminal("step_succeeded")).isFalse();
    assertThat(Outcome.isTerminal(null)).isFalse();
  }

  @Test
  void theWireNameIsLowerCase() {
    assertThat(Outcome.NEEDS_REVIEW.wire()).isEqualTo("needs_review");
    assertThat(Outcome.OPEN.wire()).isEqualTo("open");
  }
}

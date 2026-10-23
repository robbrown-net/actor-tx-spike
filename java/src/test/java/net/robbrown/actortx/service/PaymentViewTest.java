package net.robbrown.actortx.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.robbrown.actortx.payment.PaymentSnapshot;
import net.robbrown.actortx.payment.PaymentSnapshot.HistoryEntry;
import net.robbrown.actortx.payment.Phase;
import net.robbrown.actortx.payment.Result;
import net.robbrown.actortx.payment.Steps;
import org.junit.jupiter.api.Test;

class PaymentViewTest {

  private static PaymentView of(Phase phase, Result result) {
    return PaymentView.of(
        new PaymentSnapshot(
            "t",
            phase,
            "p",
            List.of("pay"),
            List.of(new HistoryEntry(1, phase, "k", "m")),
            result));
  }

  @Test
  void aTransactionInFlightHasNoOutcome() {
    PaymentView view = of(Steps.PAY.phase(), null);

    assertThat(view.outcome()).isNull();
    assertThat(view.phase()).isEqualTo("paying");
    assertThat(view.history()).containsExactly(new PaymentView.Entry(1, "paying", "k", "m"));
  }

  @Test
  void failuresAndReviewsCarryTheirReason() {
    assertThat(of(Phase.FAILED, new Result.Failed("no_quote")).outcome())
        .isEqualTo(new PaymentView.Outcome("failed", "no_quote", null));
    assertThat(of(Phase.NEEDS_REVIEW, new Result.NeedsReview("in_doubt: pay")).outcome())
        .isEqualTo(new PaymentView.Outcome("needs_review", "in_doubt: pay", null));
  }
}

package net.robbrown.actortx.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.robbrown.actortx.payment.Result.Completed;
import net.robbrown.actortx.payment.Result.Failed;
import net.robbrown.actortx.payment.Result.NeedsReview;
import org.junit.jupiter.api.Test;

class OutcomeTest {

  @Test
  void everyResultKnowsItsOutcome() {
    assertThat(new Completed(null).outcome()).isEqualTo(Outcome.COMPLETED);
    assertThat(new Failed("x").outcome()).isEqualTo(Outcome.FAILED);
    assertThat(new NeedsReview("x").outcome()).isEqualTo(Outcome.NEEDS_REVIEW);
  }

  @Test
  void outcomesAreNamedInLowerCaseOnTheWire() throws Exception {
    assertThat(Outcome.NEEDS_REVIEW.wire()).isEqualTo("needs_review");
    assertThat(new ObjectMapper().writeValueAsString(Outcome.COMPLETED)).isEqualTo("\"completed\"");
    assertThat(Outcome.fromWire("failed")).contains(Outcome.FAILED);
    assertThat(Outcome.fromWire("open")).isEmpty();
  }
}

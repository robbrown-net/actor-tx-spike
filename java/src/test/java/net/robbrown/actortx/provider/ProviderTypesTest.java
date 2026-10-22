package net.robbrown.actortx.provider;

import static org.assertj.core.api.Assertions.assertThat;

import net.robbrown.actortx.selection.Candidate;
import org.junit.jupiter.api.Test;

class ProviderTypesTest {

  @Test
  void healthStatesHaveWireNamesAndParseBack() {
    assertThat(HealthState.QUARANTINED.wire()).isEqualTo("quarantined");
    assertThat(HealthState.parse("healthy")).contains(HealthState.HEALTHY);
    assertThat(HealthState.parse("down")).contains(HealthState.DOWN);
    assertThat(HealthState.parse("weird")).isEmpty();
    assertThat(HealthState.parse(null)).isEmpty();
  }

  @Test
  void aQuoteExpiresAtItsExpiryNotBefore() {
    Quote quote = new Quote("p", "r", 100_000_000L, 5, 1_000);
    assertThat(quote.expired(999)).isFalse();
    assertThat(quote.expired(1_000)).isTrue();
  }

  @Test
  void aCandidateCanBeScored() {
    Quote quote = new Quote("p", "r", 100_000_000L, 5, 1_000);
    Candidate candidate = new Candidate("p", quote, 95, 10, 500, HealthState.HEALTHY, 0);
    assertThat(candidate.withScore(0.75).score()).isEqualTo(0.75);
    assertThat(candidate.score()).isZero();
  }

  @Test
  void operationsHaveWireNamesAndABulkheadClass() {
    assertThat(Operation.ACCEPT_QUOTE.wire()).isEqualTo("accept_quote");
    assertThat(Operation.REQUEST_QUOTE.bulkheadClass()).isEqualTo(BulkheadClass.QUOTE);
    assertThat(Operation.PAY.bulkheadClass()).isEqualTo(BulkheadClass.STEP);
    assertThat(Operation.REFUND.bulkheadClass()).isEqualTo(BulkheadClass.PROTECT);
    assertThat(Operation.PROBE.bulkheadClass()).isEqualTo(BulkheadClass.PROTECT);
  }

  @Test
  void everyAnswerHasATagAndOnlyOkIsOk() {
    assertThat(
            java.util.List.of(
                new Answer.Ok<>("v"),
                new Answer.Rejected<String>("r"),
                new Answer.Error<String>("e"),
                new Answer.Timeout<String>(),
                new Answer.InDoubt<String>(),
                new Answer.NotFound<String>(),
                new Answer.Unknown<String>()))
        .extracting(Answer::tag)
        .containsExactly("ok", "rejected", "error", "timeout", "in_doubt", "not_found", "unknown");
    assertThat(new Answer.Ok<>("v").isOk()).isTrue();
    assertThat(new Answer.Timeout<String>().isOk()).isFalse();
  }
}

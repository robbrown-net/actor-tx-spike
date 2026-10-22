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
}

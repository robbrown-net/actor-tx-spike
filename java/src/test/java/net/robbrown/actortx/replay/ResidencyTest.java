package net.robbrown.actortx.replay;

import static net.robbrown.actortx.replay.ReplayFixtures.m;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Each transaction sits exactly in the logs its own decision names: no leaks, nothing missing. */
class ResidencyTest {

  /** A tx_received whose decision names the logs the transaction must live in. */
  private static Map<String, Object> received(String tx, String... logs) {
    return m(
        "n",
        1L,
        "at",
        1L,
        "tx",
        tx,
        "kind",
        "tx_received",
        "actor",
        "payment",
        "data",
        m("eligibility", m("logs", List.of(logs))));
  }

  private static Map<String, Day> logs(List<Map<String, Object>> in, List<Map<String, Object>> us) {
    return Map.of("IN", Replay.fromRecords(in), "US", Replay.fromRecords(us));
  }

  @Test
  void aTransactionInExactlyItsOwnLogsIsResident() {
    Map<String, Day> days = logs(List.of(received("a", "IN")), List.of(received("b", "US")));
    Residency.Result result = Residency.check(days);
    assertThat(result.ok()).isTrue();
    assertThat(result.transactions()).isEqualTo(2);
    assertThat(Residency.logsHolding(days, "a")).containsExactly("IN");
  }

  @Test
  void aTransactionRecordedWhereItMustNotBeIsALeak() {
    Map<String, Day> days = logs(List.of(received("a", "IN")), List.of(received("a", "IN")));
    Residency.Result result = Residency.check(days);
    assertThat(result.ok()).isFalse();
    assertThat(result.violations()).hasSize(1);
    Residency.Violation leak = result.violations().get(0);
    assertThat(leak.rule()).isEqualTo(Rule.RESIDENCY_LEAK);
    assertThat(leak.log()).isEqualTo("US");
    assertThat(leak.detail()).contains("which it must not be (expected [\"IN\"])");
  }

  @Test
  void aTransactionMissingFromALogItMustBeInIsCaught() {
    Map<String, Day> days = logs(List.of(received("c", "IN", "US")), List.of());
    Residency.Result result = Residency.check(days);
    assertThat(result.violations()).hasSize(1);
    assertThat(result.violations().get(0).rule()).isEqualTo(Rule.RESIDENCY_MISSING);
    assertThat(result.violations().get(0).log()).isEqualTo("US");
    assertThat(result.violations().get(0).detail())
        .contains("is missing from US, where it must be recorded");
  }

  @Test
  void withoutADecisionRecordedTheLogsItIsFoundInAreTheExpectedOnes() {
    Map<String, Object> bare = m("n", 1L, "at", 1L, "tx", "d", "kind", "step_started", "data", m());
    Map<String, Day> days = logs(List.of(bare), List.of());
    assertThat(Residency.check(days).ok()).isTrue();
  }

  @Test
  void anEmptySetOfLogsHasNoTransactions() {
    assertThat(Residency.check(Map.of()).transactions()).isZero();
  }
}

package net.robbrown.actortx.replay;

import static net.robbrown.actortx.replay.ReplayFixtures.drop;
import static net.robbrown.actortx.replay.ReplayFixtures.edit;
import static net.robbrown.actortx.replay.ReplayFixtures.goodDay;
import static net.robbrown.actortx.replay.ReplayFixtures.kind;
import static net.robbrown.actortx.replay.ReplayFixtures.with;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import net.robbrown.actortx.replay.Compare.Divergence;
import org.junit.jupiter.api.Test;

/** Two days matched by transaction id: what agrees, what is only in one, and where they differ. */
class CompareTest {

  private static Day good() {
    return Replay.fromRecords(goodDay());
  }

  @Test
  void aDayIsIdenticalToItself() {
    Compare.Diff same = Compare.diff(good(), good());
    assertThat(same.common()).isEqualTo(5);
    assertThat(same.identical()).isEqualTo(5);
    assertThat(same.divergences()).isEmpty();
  }

  @Test
  void differencesAreLocatedAndTransactionsOnlyInOneDayAreNamed() {
    // the other system routes "happy" through "second" and never ran "failed"
    List<Map<String, Object>> otherRecords =
        drop(
            edit(
                goodDay(),
                kind("tx_economics", "happy"),
                r -> with(r, "second", "data", "provider")),
            r -> "failed".equals(r.get("tx")));
    Day other = Replay.fromRecords(otherRecords);

    Compare.Diff d = Compare.diff(good(), other);
    assertThat(d.common()).isEqualTo(4);
    assertThat(d.onlyA()).containsExactly("failed");
    assertThat(d.onlyB()).isEmpty();
    assertThat(d.divergences())
        .extracting(Divergence::field)
        .contains(Compare.Field.PROVIDER, Compare.Field.NET, Compare.Field.STEPS);
    Divergence provider =
        d.divergences().stream()
            .filter(x -> x.field() == Compare.Field.PROVIDER)
            .findFirst()
            .orElseThrow();
    assertThat(provider.tx()).isEqualTo("happy");
    assertThat(provider.a()).isEqualTo("best");
    assertThat(provider.b()).isEqualTo("second");
    assertThat(d.identical()).isEqualTo(3);

    Compare.Diff reverse = Compare.diff(other, good());
    assertThat(reverse.onlyB()).containsExactly("failed");
  }

  @Test
  void anOutcomeDifferenceIsALocatedDivergence() {
    Day other =
        Replay.fromRecords(
            drop(
                goodDay(),
                r -> "tx_completed".equals(r.get("kind")) && "mixed".equals(r.get("tx"))));
    Compare.Diff d = Compare.diff(good(), other);
    assertThat(d.divergences()).hasSize(1);
    assertThat(d.divergences().get(0).field()).isEqualTo(Compare.Field.OUTCOME);
    assertThat(d.divergences().get(0).a()).isEqualTo(Outcome.COMPLETED);
    assertThat(d.divergences().get(0).b()).isEqualTo(Outcome.OPEN);
    assertThat(d.identical()).isEqualTo(4);
  }
}

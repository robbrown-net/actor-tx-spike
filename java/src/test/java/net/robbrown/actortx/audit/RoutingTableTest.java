package net.robbrown.actortx.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RoutingTableTest {

  private static final Map<String, Object> IN_ONLY = decision("IN");

  private static Map<String, Object> decision(String... logs) {
    return Map.of("eligibility", Map.of("logs", List.of(logs)));
  }

  @Test
  void aDecisionTeachesTheLogsOfItsTransactionAndAResumedOneToo() {
    RoutingTable table = new RoutingTable();
    table.learn("tx_received", "t1", IN_ONLY);
    table.learn("tx_resumed", "t2", decision("US"));
    assertThat(table.targets("t1")).containsExactly("IN");
    assertThat(table.targets("t2")).containsExactly("US");
  }

  @Test
  void dataThatCarriesNoUsableLogsTeachesNothing() {
    RoutingTable table = new RoutingTable();
    table.learn("tx_received", "t1", null);
    table.learn("tx_received", "t2", "not a map");
    table.learn("tx_received", "t3", Map.of("eligibility", "not a map"));
    table.learn("tx_received", "t4", Map.of("eligibility", Map.of("logs", "IN")));
    assertThat(table.logsByTx()).isEmpty();
    assertThat(table.targets("t4")).containsExactly(RoutingTable.GLOBAL);
  }

  @Test
  void anEventWithNoTransactionOrNoRoutingGoesToGlobal() {
    RoutingTable table = new RoutingTable();
    assertThat(table.targets(null)).containsExactly(RoutingTable.GLOBAL);
    assertThat(table.targets("stray")).containsExactly(RoutingTable.GLOBAL);
  }

  @Test
  void theCostingClosesARoutingUnlessTheTransactionIsInReview() {
    RoutingTable table = new RoutingTable();
    table.learn("tx_received", "plain", IN_ONLY);
    table.close("tx_costed", "plain");
    assertThat(table.logsByTx()).isEmpty();

    table.learn("tx_received", "review", IN_ONLY);
    table.learn("needs_review", "review", null);
    table.close("tx_costed", "review");
    assertThat(table.logsByTx()).containsKey("review");
    assertThat(table.reviewing()).containsExactly("review");

    table.close("review_resolved", "review");
    assertThat(table.logsByTx()).isEmpty();
    assertThat(table.reviewing()).isEmpty();
  }

  @Test
  void otherEventsLeaveTheRoutingAlone() {
    RoutingTable table = new RoutingTable();
    table.learn("tx_received", "t", IN_ONLY);
    table.close("step_started", "t");
    assertThat(table.targets("t")).containsExactly("IN");
  }

  @Test
  void replayingARecordLearnsAndClosesAsItHappened() {
    RoutingTable table = new RoutingTable();
    table.replay(Map.of("kind", "tx_received", "tx", "t", "data", IN_ONLY));
    assertThat(table.targets("t")).containsExactly("IN");
    table.replay(Map.of("kind", "tx_costed", "tx", "t", "data", Map.of()));
    assertThat(table.logsByTx()).isEmpty();
  }

  @Test
  void theViewsOfTheTableAreCopies() {
    RoutingTable table = new RoutingTable();
    table.learn("tx_received", "t", IN_ONLY);
    table.logsByTx().clear();
    table.reviewing().add("x");
    assertThat(table.logsByTx()).containsKey("t");
    assertThat(table.reviewing()).isEmpty();
  }
}

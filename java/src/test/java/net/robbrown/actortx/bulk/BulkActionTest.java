package net.robbrown.actortx.bulk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import net.robbrown.actortx.events.EventSink;
import net.robbrown.actortx.service.InvalidInputException;
import org.junit.jupiter.api.Test;

class BulkActionTest {

  private final CountDownLatch hold = new CountDownLatch(1);
  private final BulkRunner runner =
      new BulkRunner(
          command -> new Submission.Invalid(List.of("no")),
          Clock.systemUTC(),
          millis -> hold.await(),
          new Random(1),
          EventSink.NONE);
  private final BulkAction action = new BulkAction(runner);

  @Test
  void aBatchIsStartedStoppedAndCleared() {
    assertThat(action.name()).isEqualTo("bulk");

    Map<String, Object> started =
        action.apply(
            Map.of(
                "action",
                "bulk",
                "do",
                "start",
                "count",
                3,
                "min_amount",
                "1",
                "max_amount",
                "5",
                "interval_ms",
                100));
    assertThat(started).containsKey("batch");
    assertThatThrownBy(() -> action.apply(Map.of("do", "clear")))
        .isInstanceOf(InvalidInputException.class)
        .hasMessage("stop the batch first");

    assertThat(action.apply(Map.of("do", "stop"))).containsEntry("stopped", true);
    hold.countDown();
    assertThat(action.apply(Map.of("do", "clear"))).containsEntry("cleared", true);
  }

  @Test
  void anythingElseIsRefused() {
    assertThatThrownBy(() -> action.apply(Map.of("do", "dance")))
        .isInstanceOf(InvalidInputException.class)
        .hasMessage("unknown bulk command");
  }
}

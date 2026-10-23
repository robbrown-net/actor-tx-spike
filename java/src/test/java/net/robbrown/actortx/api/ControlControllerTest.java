package net.robbrown.actortx.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.robbrown.actortx.service.CutoffBook;
import net.robbrown.actortx.service.InvalidInputException;
import org.junit.jupiter.api.Test;

class ControlControllerTest {

  private final List<String> calls = new ArrayList<>();
  private final CutoffBook cutoffs = new CutoffBook();
  private final List<String> events = new ArrayList<>();
  private final ControlController controller =
      new ControlController(
          (id, action, params) -> {
            calls.add(id + ":" + action + ":" + params.get("rate"));
            return Map.of("provider", id, "change", action);
          },
          cutoffs,
          (tx, actor, kind, message, data) -> events.add(kind));

  @Test
  void aProviderCommandIsPassedToTheControl() {
    ControlController.Done done =
        controller.control(
            Map.of("action", "provider", "id", "northwind", "do", "reprice", "rate", 1.2));

    assertThat(done.ok()).isTrue();
    assertThat(done.result())
        .containsEntry("provider", "northwind")
        .containsEntry("change", "reprice");
    assertThat(calls).containsExactly("northwind:reprice:1.2");
  }

  @Test
  void anyOtherActionIsRefused() {
    assertThatThrownBy(() -> controller.control(Map.of("action", "dance")))
        .isInstanceOf(InvalidInputException.class)
        .hasMessage("unknown action dance");
    assertThat(calls).isEmpty();
  }

  @Test
  void aCutoffIsSetAnnouncedAndCleared() {
    ControlController.Done set =
        controller.control(Map.of("action", "cutoff", "region", "IN", "in_seconds", 60));

    assertThat(set.result()).containsEntry("region", "IN").containsEntry("in_seconds", 60.0);
    assertThat(cutoffs.remaining(System.currentTimeMillis())).containsKey("IN");
    assertThat(events).containsExactly("cutoff_set");

    ControlController.Done cleared = controller.control(Map.of("action", "cutoff", "region", "IN"));

    assertThat(cleared.result()).containsEntry("cleared", true);
    assertThat(cutoffs.remaining(System.currentTimeMillis())).isEmpty();
  }
}

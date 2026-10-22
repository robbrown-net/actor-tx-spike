package net.robbrown.actortx.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.robbrown.actortx.service.InvalidInputException;
import org.junit.jupiter.api.Test;

class ControlControllerTest {

  private final List<String> calls = new ArrayList<>();
  private final ControlController controller =
      new ControlController(
          (id, action, params) -> {
            calls.add(id + ":" + action + ":" + params.get("rate"));
            return Map.of("provider", id, "change", action);
          });

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
}

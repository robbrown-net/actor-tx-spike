package net.robbrown.actortx.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import net.robbrown.actortx.service.ControlAction;
import net.robbrown.actortx.service.InvalidInputException;
import org.junit.jupiter.api.Test;

class ControlControllerTest {

  private static ControlAction echo(String name) {
    return new ControlAction() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public Map<String, Object> apply(Map<String, Object> command) {
        return Map.of("echo", command.get("value"));
      }
    };
  }

  private final ControlController controller =
      new ControlController(List.of(echo("one"), echo("two")));

  @Test
  void aCommandIsHandedToTheActionOfThatName() {
    ControlController.Done done = controller.control(Map.of("action", "two", "value", 7));

    assertThat(done.ok()).isTrue();
    assertThat(done.result()).containsEntry("echo", 7);
  }

  @Test
  void anyOtherActionIsRefused() {
    assertThatThrownBy(() -> controller.control(Map.of("action", "dance")))
        .isInstanceOf(InvalidInputException.class)
        .hasMessage("unknown action dance");
    assertThatThrownBy(() -> controller.control(Map.of()))
        .isInstanceOf(InvalidInputException.class);
  }

  @Test
  void commandsThePortLacksSaySoInsteadOfPretending() {
    assertThatThrownBy(() -> controller.control(Map.of("action", "auto")))
        .isInstanceOf(InvalidInputException.class)
        .hasMessage("auto is not available in the Java port yet");
  }
}

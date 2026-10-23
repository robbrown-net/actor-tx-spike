package net.robbrown.actortx.api;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.robbrown.actortx.service.ControlAction;
import net.robbrown.actortx.service.InvalidInputException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The operator's control surface: {@code {"action": name, ...}}, dispatched by name. */
@RestController
@RequestMapping("/api")
public class ControlController {

  /** The success envelope. */
  public record Done(boolean ok, Map<String, Object> result) {}

  private final Map<String, ControlAction> actions;

  /** Wires every action the operator can give. */
  public ControlController(List<ControlAction> actions) {
    this.actions =
        actions.stream().collect(Collectors.toMap(ControlAction::name, Function.identity()));
  }

  /** Apply a control command. */
  @PostMapping("/control")
  public Done control(@RequestBody Map<String, Object> command) {
    ControlAction action = actions.get(String.valueOf(command.get("action")));
    if (action == null) {
      throw new InvalidInputException(List.of("unknown action " + command.get("action")));
    }
    return new Done(true, action.apply(command));
  }
}

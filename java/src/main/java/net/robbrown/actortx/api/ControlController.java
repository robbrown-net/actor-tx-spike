package net.robbrown.actortx.api;

import java.util.List;
import java.util.Map;
import java.util.Set;
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

  private static final Set<String> NOT_YET = Set.of("auto", "pace", "policy", "lab");

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
      throw new InvalidInputException(List.of(refusal(String.valueOf(command.get("action")))));
    }
    return new Done(true, action.apply(command));
  }

  // Dashboard commands the Java port does not offer yet say so rather than pretending.
  private static String refusal(String action) {
    return NOT_YET.contains(action)
        ? action + " is not available in the Java port yet"
        : "unknown action " + action;
  }
}

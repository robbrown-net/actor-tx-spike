package net.robbrown.actortx.api;

import java.util.Map;
import net.robbrown.actortx.service.InvalidInputException;
import net.robbrown.actortx.service.ProviderControl;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The operator's control surface: {@code {"action": "provider", "id": ..., "do": ...}}. */
@RestController
@RequestMapping("/api")
public class ControlController {

  /** The success envelope. */
  public record Done(boolean ok, Map<String, Object> result) {}

  private final ProviderControl control;

  /** Wires the control the API fronts. */
  public ControlController(ProviderControl control) {
    this.control = control;
  }

  /** Apply a control command. */
  @PostMapping("/control")
  public Done control(@RequestBody Map<String, Object> command) {
    if (!"provider".equals(command.get("action"))) {
      throw new InvalidInputException(java.util.List.of("unknown action " + command.get("action")));
    }
    String id = String.valueOf(command.get("id"));
    String preset = String.valueOf(command.get("do"));
    return new Done(true, control.apply(id, preset, command));
  }
}

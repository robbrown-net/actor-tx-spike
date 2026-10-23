package net.robbrown.actortx.api;

import java.util.Map;
import net.robbrown.actortx.events.EventSink;
import net.robbrown.actortx.service.CutoffBook;
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
  private final CutoffBook cutoffs;
  private final EventSink events;

  /** Wires the control the API fronts. */
  public ControlController(ProviderControl control, CutoffBook cutoffs, EventSink events) {
    this.control = control;
    this.cutoffs = cutoffs;
    this.events = events;
  }

  /** Apply a control command. */
  @PostMapping("/control")
  public Done control(@RequestBody Map<String, Object> command) {
    if ("cutoff".equals(command.get("action"))) {
      return cutoff(command);
    }
    if (!"provider".equals(command.get("action"))) {
      throw new InvalidInputException(java.util.List.of("unknown action " + command.get("action")));
    }
    String id = String.valueOf(command.get("id"));
    String preset = String.valueOf(command.get("do"));
    return new Done(true, control.apply(id, preset, command));
  }

  // A positive in_seconds sets the region's cut-off that far ahead; zero or none clears it.
  private Done cutoff(Map<String, Object> command) {
    String region = String.valueOf(command.get("region"));
    double seconds = command.get("in_seconds") instanceof Number n ? n.doubleValue() : 0;
    if (seconds <= 0) {
      cutoffs.clear(region);
      return new Done(true, Map.of("region", region, "cleared", true));
    }
    cutoffs.set(region, System.currentTimeMillis() + Math.round(seconds * 1_000));
    events.emit(
        null,
        "operator",
        "cutoff_set",
        "operator: " + region + " cut-off in " + seconds + "s",
        Map.of("region", region));
    return new Done(true, Map.of("region", region, "in_seconds", seconds));
  }
}

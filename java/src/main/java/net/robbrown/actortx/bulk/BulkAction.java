package net.robbrown.actortx.bulk;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.robbrown.actortx.service.ControlAction;
import net.robbrown.actortx.service.InvalidInputException;

/**
 * The dashboard's {@code bulk} command: {@code do} is {@code start}, {@code stop} or {@code clear}.
 */
public final class BulkAction implements ControlAction {

  private final BulkRunner runner;

  /** Drives {@code runner}. */
  public BulkAction(BulkRunner runner) {
    this.runner = runner;
  }

  @Override
  public String name() {
    return "bulk";
  }

  @Override
  public Map<String, Object> apply(Map<String, Object> command) {
    return switch (String.valueOf(command.get("do"))) {
      case "start" -> Map.of("batch", runner.start(plan(command)));
      case "stop" -> Map.of("stopped", runner.stop());
      case "clear" -> clear();
      default -> throw new InvalidInputException(List.of("unknown bulk command"));
    };
  }

  private Map<String, Object> clear() {
    try {
      runner.clear();
    } catch (IllegalStateException running) {
      throw new InvalidInputException(List.of("stop the batch first"));
    }
    return Map.of("cleared", true);
  }

  // The command minus its verb is the plan.
  private static Map<String, Object> plan(Map<String, Object> command) {
    Map<String, Object> plan = new HashMap<>(command);
    plan.remove("do");
    plan.remove("action");
    return plan;
  }
}

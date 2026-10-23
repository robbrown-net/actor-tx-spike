package net.robbrown.actortx.service;

import java.util.Map;

/** One kind of operator command: the dashboard's {@code {"action": name, ...}}. */
public interface ControlAction {

  /** The action's name, as in {@code {"action": "cutoff"}}. */
  String name();

  /**
   * Apply the command.
   *
   * @throws InvalidInputException when the command is not valid
   */
  Map<String, Object> apply(Map<String, Object> command);
}

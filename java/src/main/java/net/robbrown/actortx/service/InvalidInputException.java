package net.robbrown.actortx.service;

import java.util.List;

/** The caller's input was not valid; carries every problem found. */
public class InvalidInputException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient List<String> errors;

  /** An exception carrying all the problems. */
  public InvalidInputException(List<String> errors) {
    super(String.join("; ", errors));
    this.errors = List.copyOf(errors);
  }

  /** Every problem found. */
  public List<String> errors() {
    return errors;
  }
}

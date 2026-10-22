package net.robbrown.actortx.policy;

import java.util.List;
import net.robbrown.actortx.validation.Validated;

/** Test helpers for unwrapping {@link Validated} results. */
final class Results {

  private Results() {}

  /** The value of a result that must be valid. */
  static <T> T valid(Validated<T> result) {
    if (result instanceof Validated.Valid<T>(T value)) {
      return value;
    }
    throw new AssertionError("expected a valid result, got " + errorsOf(result));
  }

  /** The errors of a result that must be invalid, or none if it is valid. */
  static <T> List<String> errorsOf(Validated<T> result) {
    if (result instanceof Validated.Invalid<T>(List<String> errors)) {
      return errors;
    }
    return List.of();
  }
}

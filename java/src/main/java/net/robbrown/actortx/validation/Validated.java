package net.robbrown.actortx.validation;

import java.util.List;

/**
 * Either a valid value or every problem found with the input. Construction code reports all the
 * problems at once, not just the first.
 *
 * @param <T> the validated type
 */
public sealed interface Validated<T> {

  /** The input was valid. */
  record Valid<T>(T value) implements Validated<T> {}

  /** The input was not: every message. */
  record Invalid<T>(List<String> errors) implements Validated<T> {}

  /** Valid when there are no errors, else invalid. */
  static <T> Validated<T> of(T value, List<String> errors) {
    return errors.isEmpty() ? new Valid<>(value) : new Invalid<>(List.copyOf(errors));
  }
}

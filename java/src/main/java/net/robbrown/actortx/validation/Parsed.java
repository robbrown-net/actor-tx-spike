package net.robbrown.actortx.validation;

/**
 * The result of parsing one field: a value, or a message saying what is wrong with it.
 *
 * @param <T> the parsed type
 */
public record Parsed<T>(T value, String error) {

  /** A field that parsed. */
  public static <T> Parsed<T> ok(T value) {
    return new Parsed<>(value, null);
  }

  /** A field that did not. */
  public static <T> Parsed<T> fail(String message) {
    return new Parsed<>(null, message);
  }

  /** Did it parse? */
  public boolean isOk() {
    return error == null;
  }
}

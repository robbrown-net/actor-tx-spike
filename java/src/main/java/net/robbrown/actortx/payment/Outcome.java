package net.robbrown.actortx.payment;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * The three ways a transaction can end, without the detail a {@link Result} carries. This is the
 * one place the vocabulary lives: economics, bulk sending, replay and the dashboard all use it.
 *
 * <p>The set is genuinely closed: every transaction ends in exactly one of these, and a new
 * operational state would be a different concept (not another ending).
 */
public enum Outcome {
  /** Paid and settled. */
  COMPLETED,
  /** Stopped cleanly; nothing is left half done. */
  FAILED,
  /** Waiting for a person to decide. */
  NEEDS_REVIEW;

  /**
   * The name used in JSON and the audit log: {@code completed}, {@code failed}, {@code
   * needs_review}.
   */
  @JsonValue
  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** The outcome named on the wire, if it is one. */
  public static java.util.Optional<Outcome> fromWire(String wire) {
    return java.util.Arrays.stream(values()).filter(o -> o.wire().equals(wire)).findFirst();
  }
}

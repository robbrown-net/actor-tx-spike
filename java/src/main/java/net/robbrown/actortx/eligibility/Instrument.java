package net.robbrown.actortx.eligibility;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** The kind of trade: a spot exchange or a forward. */
public enum Instrument {
  SPOT,
  FORWARD;

  /** The name used on the wire and in reasons, such as {@code "spot"}. */
  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** The instrument whose wire name is {@code value}, if there is one. */
  public static Optional<Instrument> parse(Object value) {
    return Arrays.stream(values()).filter(i -> i.wire().equals(value)).findFirst();
  }
}

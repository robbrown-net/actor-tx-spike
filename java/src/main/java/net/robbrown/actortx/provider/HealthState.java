package net.robbrown.actortx.provider;

import java.util.Arrays;
import java.util.Optional;

/** Our view of a provider's health. {@code DOWN} is a provider that cannot be reached at all. */
public enum HealthState {
  UNKNOWN,
  HEALTHY,
  DEGRADED,
  STALLED,
  QUARANTINED,
  DOWN;

  /** The state with this lower-case name ({@code "healthy"}), if there is one. */
  public static Optional<HealthState> parse(Object name) {
    return Arrays.stream(values()).filter(s -> s.wire().equals(String.valueOf(name))).findFirst();
  }

  /** The name used in JSON and in the audit log. */
  public String wire() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }
}

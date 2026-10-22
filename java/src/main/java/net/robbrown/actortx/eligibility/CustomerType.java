package net.robbrown.actortx.eligibility;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** Who the customer is: a business or a retail customer. */
public enum CustomerType {
  BUSINESS,
  RETAIL;

  /** The name used on the wire and in reasons, such as {@code "business"}. */
  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** The customer type whose wire name is {@code value}, if there is one. */
  public static Optional<CustomerType> parse(Object value) {
    return Arrays.stream(values()).filter(c -> c.wire().equals(value)).findFirst();
  }
}

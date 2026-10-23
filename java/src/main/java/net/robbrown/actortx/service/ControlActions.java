package net.robbrown.actortx.service;

import java.util.List;
import java.util.Map;
import net.robbrown.actortx.events.EventSink;
import net.robbrown.actortx.review.ReviewService;

/** The operator commands the dashboard can give. */
public final class ControlActions {

  private ControlActions() {}

  /** A named action defined by a function. */
  private record Named(
      String name, java.util.function.Function<Map<String, Object>, Map<String, Object>> body)
      implements ControlAction {
    @Override
    public Map<String, Object> apply(Map<String, Object> command) {
      return body.apply(command);
    }
  }

  /** {@code provider}: slow, stall, error... a named misbehaviour of one provider. */
  public static ControlAction provider(ProviderControl control) {
    return new Named(
        "provider",
        c -> control.apply(String.valueOf(c.get("id")), String.valueOf(c.get("do")), c));
  }

  /** {@code cutoff}: a positive {@code in_seconds} sets a region's cut-off, otherwise clears it. */
  public static ControlAction cutoff(CutoffBook cutoffs, EventSink events) {
    return new Named("cutoff", c -> setCutoff(cutoffs, events, c));
  }

  private static Map<String, Object> setCutoff(
      CutoffBook cutoffs, EventSink events, Map<String, Object> command) {
    String region = String.valueOf(command.get("region"));
    double seconds = command.get("in_seconds") instanceof Number n ? n.doubleValue() : 0;
    if (seconds <= 0) {
      cutoffs.clear(region);
      return Map.of("region", region, "cleared", true);
    }
    cutoffs.set(region, System.currentTimeMillis() + Math.round(seconds * 1_000));
    events.emit(
        null,
        "operator",
        "cutoff_set",
        "operator: " + region + " cut-off in " + seconds + "s",
        Map.of("region", region));
    return Map.of("region", region, "in_seconds", seconds);
  }

  /** {@code submit}: send one payment. */
  public static ControlAction submit(PaymentService payments) {
    return new Named("submit", c -> Map.of("id", payments.submit(commandOf(c))));
  }

  // The dashboard's field names mapped onto a payment command.
  static PaymentCommand commandOf(Map<String, Object> c) {
    Object amount = c.getOrDefault("amount", 1_000);
    return new PaymentCommand(
        new java.math.BigDecimal(String.valueOf(amount)),
        text(c, "merchant"),
        text(c, "customer"),
        text(c, "origin"),
        text(c, "region"),
        text(c, "instrument"),
        text(c, "customer_type"),
        text(c, "source_currency"),
        text(c, "destination_currency"),
        null);
  }

  private static String text(Map<String, Object> c, String key) {
    Object value = c.get(key);
    return value instanceof String s && !s.isEmpty() ? s : null;
  }

  /** {@code admission}: change the most transactions in flight ({@code none} removes the limit). */
  public static ControlAction admission(Admission admission, EventSink events) {
    return new Named("admission", c -> changeAdmission(admission, events, c));
  }

  private static Map<String, Object> changeAdmission(
      Admission admission, EventSink events, Map<String, Object> command) {
    Object wanted = command.get("max_in_flight");
    admission.setLimit(limitOf(wanted));
    events.emit(
        null, "operator", "admission_changed", "operator: admission limit " + wanted, Map.of());
    return admission.stats();
  }

  private static final java.util.Set<String> NO_LIMIT = java.util.Set.of("null", "", "none", "0");

  private static int limitOf(Object wanted) {
    String text = String.valueOf(wanted);
    if (NO_LIMIT.contains(text)) {
      return Integer.MAX_VALUE;
    }
    try {
      return Math.max(1, Integer.parseInt(text));
    } catch (NumberFormatException notANumber) {
      throw new InvalidInputException(List.of("max_in_flight: must be a whole number or none"));
    }
  }

  /** {@code review}: check, retry or resolve a transaction waiting for a person. */
  public static ControlAction review(ReviewService reviews) {
    return new Named("review", c -> Map.of("reply", reviews.command(c)));
  }

  /** An action the Java port does not offer yet: says so rather than pretending. */
  public static ControlAction notYet(String name) {
    return new Named(
        name,
        c -> {
          throw new InvalidInputException(List.of(name + " is not available in the Java port yet"));
        });
  }
}

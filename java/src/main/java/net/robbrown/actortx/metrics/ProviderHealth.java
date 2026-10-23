package net.robbrown.actortx.metrics;

import java.util.Map;

/**
 * The latest known health of one provider, as its health events last described it.
 *
 * @param state the provider's state, such as {@code "healthy"} or {@code "degraded"}
 * @param p95Ms the 95th-percentile latency it was showing, in milliseconds
 * @param errorRate the share of its calls that failed
 * @param inFlight the calls it had in progress
 * @param breaker its circuit breaker, such as {@code "closed"}
 */
public record ProviderHealth(
    String state, double p95Ms, double errorRate, long inFlight, String breaker) {

  /** Keep the health fields of an event's data; anything else is ignored. */
  static ProviderHealth from(Map<String, Object> data) {
    return new ProviderHealth(
        text(data, "state"),
        number(data, "p95_ms"),
        number(data, "error_rate"),
        (long) number(data, "in_flight"),
        text(data, "breaker"));
  }

  private static String text(Map<String, Object> data, String key) {
    return String.valueOf(data.getOrDefault(key, "unknown"));
  }

  private static double number(Map<String, Object> data, String key) {
    return data.get(key) instanceof Number n ? n.doubleValue() : 0.0;
  }
}

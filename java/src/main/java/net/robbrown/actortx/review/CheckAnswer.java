package net.robbrown.actortx.review;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one provider holds for one step of a transaction, as a check found it.
 *
 * @param op the step ({@code "accept_quote"}, {@code "authorise"}, {@code "pay"} or {@code
 *     "settle"})
 * @param key the idempotency key the step is held under
 * @param answer the provider's answer, as its short word ({@code "ok"}, {@code "not_found"}...)
 */
public record CheckAnswer(String op, String key, String answer) {

  /**
   * The answer as the audit log records it, with its fields in a fixed order.
   *
   * @return a new map of {@code op}, {@code key} and {@code answer}
   */
  public Map<String, Object> toMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("op", op);
    map.put("key", key);
    map.put("answer", answer);
    return map;
  }
}

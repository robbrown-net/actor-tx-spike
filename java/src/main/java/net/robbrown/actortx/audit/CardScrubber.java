package net.robbrown.actortx.audit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/**
 * Redacts anything in free text that looks like a payment card number: thirteen to nineteen digits,
 * optionally separated by single spaces or hyphens, that pass the Luhn check. Defence in depth; the
 * real control is that the orchestrator only ever handles provider tokens.
 */
final class CardScrubber {

  /** What replaces a card number. */
  static final String REDACTED = "[REDACTED-PAN]";

  private static final Pattern CARD_LIKE = Pattern.compile("\\d(?:[ -]?\\d){12,18}");

  private CardScrubber() {}

  /**
   * Redact card numbers in any value: strings are scrubbed, maps and lists are scrubbed inside.
   *
   * @param value anything JSON-like
   * @return the same shape with card numbers redacted
   */
  static Object scrub(Object value) {
    if (value instanceof String text) {
      return CARD_LIKE.matcher(text).replaceAll(match -> redact(match));
    }
    if (value instanceof Map<?, ?> map) {
      return scrubMap(map);
    }
    if (value instanceof List<?> list) {
      return list.stream().map(item -> scrub(item)).toList();
    }
    return value;
  }

  private static Map<Object, Object> scrubMap(Map<?, ?> map) {
    Map<Object, Object> out = new LinkedHashMap<>();
    map.forEach((key, value) -> out.put(key, scrub(value)));
    return out;
  }

  private static String redact(MatchResult match) {
    return luhn(match.group()) ? REDACTED : match.group();
  }

  /**
   * Whether the digits of a candidate pass the Luhn check.
   *
   * @param candidate a run of digits, possibly with separators
   * @return true if the check digit is consistent
   */
  static boolean luhn(String candidate) {
    String digits = candidate.replaceAll("\\D", "");
    int sum = 0;
    for (int i = 0; i < digits.length(); i++) {
      int digit = digits.charAt(digits.length() - 1 - i) - '0';
      sum += i % 2 == 1 ? doubled(digit) : digit;
    }
    return sum % 10 == 0;
  }

  private static int doubled(int digit) {
    int twice = digit * 2;
    return twice > 9 ? twice - 9 : twice;
  }
}

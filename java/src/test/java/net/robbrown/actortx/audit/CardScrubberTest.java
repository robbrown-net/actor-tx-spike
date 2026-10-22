package net.robbrown.actortx.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CardScrubberTest {

  private static String scrubText(String text) {
    return (String) CardScrubber.scrub(text);
  }

  @Test
  void luhnValidCardNumbersAreRedactedWhateverTheSeparators() {
    assertThat(scrubText("paid with 4111 1111 1111 1111 ok"))
        .isEqualTo("paid with [REDACTED-PAN] ok");
    assertThat(scrubText("card 5500-0000-0000-0004.")).isEqualTo("card [REDACTED-PAN].");
    assertThat(scrubText("amex 378282246310005")).isEqualTo("amex [REDACTED-PAN]");
  }

  @Test
  void otherDigitRunsAreLeftAlone() {
    assertThat(scrubText("order 1234567890123456 ref")).isEqualTo("order 1234567890123456 ref");
    assertThat(scrubText("amount 1000 minor units")).isEqualTo("amount 1000 minor units");
  }

  @Test
  void scrubbingReachesInsideMapsAndListsButNotKeysOrNumbers() {
    Map<String, Object> inner = new LinkedHashMap<>();
    inner.put("card", "378282246310005");
    inner.put("nothing", null);
    Map<String, Object> outer = Map.of("a", List.of(inner, "4111111111111111"), "b", 7);
    Map<?, ?> scrubbed = (Map<?, ?>) CardScrubber.scrub(outer);
    List<?> items = (List<?>) scrubbed.get("a");
    Map<?, ?> copy = (Map<?, ?>) items.getFirst();
    assertThat(scrubbed.get("b")).isEqualTo(7);
    assertThat(copy.get("card")).isEqualTo("[REDACTED-PAN]");
    assertThat(copy.containsKey("nothing")).isTrue();
    assertThat(items.get(1)).isEqualTo("[REDACTED-PAN]");
  }

  @Test
  void valuesThatAreNotTextOrStructureAreReturnedAsIs() {
    assertThat(CardScrubber.scrub(null)).isNull();
    assertThat(CardScrubber.scrub(42)).isEqualTo(42);
  }

  @Test
  void theLuhnCheckTellsValidFromInvalid() {
    assertThat(CardScrubber.luhn("4111111111111111")).isTrue();
    assertThat(CardScrubber.luhn("1234567890123456")).isFalse();
  }
}

package net.robbrown.actortx.guarantee;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.robbrown.actortx.guarantee.Guarantee.Evaluation;
import net.robbrown.actortx.validation.Validated;
import net.robbrown.actortx.validation.Validated.Invalid;
import net.robbrown.actortx.validation.Validated.Valid;
import org.junit.jupiter.api.Test;

class GuaranteeTest {

  private static final String PERIOD_HINT = "period: required (e.g. \"2026-W42\")";

  static Map<String, Object> base(Object rate) {
    Map<String, Object> data = new HashMap<>();
    data.put("merchant_id", "acme");
    data.put("period", "W1");
    data.put("rate", rate);
    return data;
  }

  static List<String> errorsOf(Validated<?> result) {
    return result instanceof Invalid<?> invalid ? invalid.errors() : List.of();
  }

  static Guarantee valueOf(Validated<Guarantee> result) {
    assertThat(result).isInstanceOf(Valid.class);
    return ((Valid<Guarantee>) result).value();
  }

  @Test
  void rateAcceptsDecimalsAndStringsAndRejectsJunk() {
    assertThat(valueOf(Guarantee.fromMap(base("1.05"))).rate()).isEqualTo(105_000_000L);
    assertThat(errorsOf(Guarantee.fromMap(base("abc"))))
        .containsExactly("rate: must be a positive number");
    assertThat(errorsOf(Guarantee.fromMap(base(null))))
        .containsExactly("rate: must be a positive number");
    assertThat(errorsOf(Guarantee.fromMap(base(-1.0))))
        .containsExactly("rate: must be a positive number");
  }

  @Test
  void everyKindOfRateNumberIsScaledExactly() {
    assertThat(valueOf(Guarantee.fromMap(base(2)))).isEqualTo(valueOf(Guarantee.fromMap(base(2L))));
    assertThat(valueOf(Guarantee.fromMap(base(2))))
        .isEqualTo(valueOf(Guarantee.fromMap(base("2"))));
    assertThat(valueOf(Guarantee.fromMap(base(1.08))).rate()).isEqualTo(108_000_000L);
    assertThat(valueOf(Guarantee.fromMap(base(1.0877))).rate()).isEqualTo(108_770_000L);
  }

  @Test
  void rateRejectsNonNumbersZeroAndNaN() {
    assertThat(errorsOf(Guarantee.fromMap(base(0)))).hasSize(1);
    assertThat(errorsOf(Guarantee.fromMap(base("")))).hasSize(1);
    assertThat(errorsOf(Guarantee.fromMap(base(Double.NaN)))).hasSize(1);
    assertThat(errorsOf(Guarantee.fromMap(base("1e30")))).hasSize(1);
    assertThat(errorsOf(Guarantee.fromMap(base(Boolean.TRUE)))).hasSize(1);
  }

  @Test
  void aGuaranteeIsValidatedAndEvaluatesMarginAgainstTheGuarantee() {
    Map<String, Object> data = Map.of("merchant_id", "acme", "period", "2026-W42", "rate", 1.08);
    Guarantee guarantee = valueOf(Guarantee.fromMap(data));

    assertThat(guarantee).isEqualTo(new Guarantee("acme", "2026-W42", 108_000_000L));
    assertThat(guarantee.evaluate(1000, 1085)).isEqualTo(new Evaluation(1080, 1085, 5));
    assertThat(guarantee.evaluate(1000, 1070).margin()).isEqualTo(-10);
  }

  @Test
  void badInputIsRejectedWithEveryReason() {
    Map<String, Object> data = new HashMap<>();
    data.put("merchant_id", "");
    data.put("period", null);
    data.put("rate", 0);

    assertThat(errorsOf(Guarantee.fromMap(data)))
        .containsExactly("merchant_id: required", PERIOD_HINT, "rate: must be a positive number");
  }

  @Test
  void missingFieldsAreReportedAsRequired() {
    assertThat(errorsOf(Guarantee.fromMap(Map.of())))
        .containsExactly("merchant_id: required", PERIOD_HINT, "rate: must be a positive number");
  }

  @Test
  void aMerchantMustBeANonEmptyString() {
    Map<String, Object> notText = base(1);
    notText.put("merchant_id", 5);
    assertThat(errorsOf(Guarantee.fromMap(notText))).containsExactly("merchant_id: required");

    Map<String, Object> blank = base(1);
    blank.put("merchant_id", " ");
    assertThat(valueOf(Guarantee.fromMap(blank)).merchantId()).isEqualTo(" ");
  }

  @Test
  void aPeriodMustBeANonEmptyString() {
    Map<String, Object> empty = base(1);
    empty.put("period", "");
    assertThat(errorsOf(Guarantee.fromMap(empty))).containsExactly(PERIOD_HINT);
  }
}

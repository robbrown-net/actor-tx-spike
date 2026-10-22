package net.robbrown.actortx.guarantee;

import java.util.Map;
import net.robbrown.actortx.money.Money;
import net.robbrown.actortx.validation.FieldReader;
import net.robbrown.actortx.validation.Parsed;
import net.robbrown.actortx.validation.Validated;

/**
 * A standing rate guarantee a merchant holds for a period (for example a weekly guaranteed rate).
 * Per transaction we may route better or worse than the guarantee; the difference is our margin
 * (positive) or exposure (negative).
 *
 * <pre>
 *   net guaranteed = amount * guaranteed_rate   (integer minor units, half-even)
 *   margin         = achieved_net - net guaranteed
 * </pre>
 *
 * @param merchantId the merchant the guarantee is held with
 * @param period the period it covers, for example {@code "2026-W42"}
 * @param rate the guaranteed rate, scaled by 10^8 (see {@link Money})
 */
public record Guarantee(String merchantId, String period, long rate) {

  /**
   * The outcome of comparing what was achieved with what was guaranteed.
   *
   * @param guaranteedNet the net amount the guarantee promised, in minor units
   * @param achievedNet the net amount actually achieved, in minor units
   * @param margin {@code achievedNet - guaranteedNet}: positive is our margin, negative exposure
   */
  public record Evaluation(long guaranteedNet, long achievedNet, long margin) {}

  private static final String PERIOD_MESSAGE = "required (e.g. \"2026-W42\")";
  private static final String RATE_MESSAGE = "must be a positive number";

  /**
   * Validate plain data (decoded JSON) into a guarantee. Keys are {@code merchant_id}, {@code
   * period} and {@code rate}; the rate may be a number or a decimal string. Every problem is
   * reported, not just the first.
   *
   * @param data the decoded fields
   * @return the guarantee, or every reason it is invalid
   */
  public static Validated<Guarantee> fromMap(Map<String, Object> data) {
    FieldReader reader =
        new FieldReader(Inputs.withMissingAsNull(data, "merchant_id", "period", "rate"), "");
    String merchant = reader.read("merchant_id", null, v -> Inputs.text(v, "required"));
    String period = reader.read("period", null, v -> Inputs.text(v, PERIOD_MESSAGE));
    long rate = reader.read("rate", 0L, v -> parseRate(v));
    Guarantee guarantee = new Guarantee(merchant, period, rate);
    return reader.finish(guarantee);
  }

  /**
   * Compare what was achieved with what was guaranteed for an amount.
   *
   * @param amountMinor the transaction amount, in minor units
   * @param achievedNet the net amount achieved, in minor units
   * @return the guaranteed net, the achieved net and the margin between them
   */
  public Evaluation evaluate(long amountMinor, long achievedNet) {
    long guaranteed = Money.convert(amountMinor, rate);
    return new Evaluation(guaranteed, achievedNet, achievedNet - guaranteed);
  }

  private static Parsed<Long> parseRate(Object value) {
    long scaled = scaledRateOrZero(value);
    return scaled > 0 ? Parsed.ok(scaled) : Parsed.fail(RATE_MESSAGE);
  }

  /** The scaled rate, or 0 for anything that is not a decimal; 0 then fails the positive check. */
  private static long scaledRateOrZero(Object value) {
    try {
      return scaledRate(value);
    } catch (NumberFormatException | ArithmeticException e) {
      return 0L;
    }
  }

  private static long scaledRate(Object value) {
    if (value instanceof String text) {
      return Money.rate(text);
    }
    if (value instanceof Number n) {
      return Inputs.isIntegral(n) ? Money.rate(n.longValue()) : Money.rate(n.doubleValue());
    }
    throw new NumberFormatException("not a number: " + value);
  }
}

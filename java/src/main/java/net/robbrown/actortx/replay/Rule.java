package net.robbrown.actortx.replay;

import java.util.Locale;

/** Every rule a replayed day is checked against. Each violation names the rule it breaks. */
public enum Rule {
  /** No transaction paid more than once. */
  SINGLE_PAY,
  /**
   * A completed transaction accepted, authorised, paid and settled, in that order, at one provider.
   */
  COMPLETED_IS_WHOLE,
  /** Nothing a provider was left holding was left un-undone. */
  FAILED_CLEAN,
  /** Once pay or settle was in doubt, we never moved to another provider. */
  NO_FAILOVER_AFTER_DOUBT,
  /** Every call used the key transaction:provider:step, and retries reused it. */
  IDEMPOTENCY_KEYS,
  /** No provider outside the recorded jurisdiction decision was used or asked. */
  ELIGIBILITY_RESPECTED,
  /** A person's resolution of a review names the outcome, who decided and why. */
  REVIEW_RECORDED,
  /** The recorded inputs, judged again under the recorded policy, give the recorded ranking. */
  SELECTION_REPRODUCIBLE,
  /** The quote had not expired when accept was asked. */
  QUOTE_VALID_AT_ACCEPT,
  /** No step began after the cut-off. */
  DEADLINE_RESPECTED,
  /** The guarantee margin follows from the recorded quotes. */
  MARGIN_ARITHMETIC,
  /** The recorded price discovery agrees with the recorded quote. */
  ECONOMICS_CONSISTENT,
  /** Time never runs backwards in the log. */
  TIME_ORDERED,
  /** A transaction is recorded in a log it must not be in. */
  RESIDENCY_LEAK,
  /** A transaction is missing from a log it must be in. */
  RESIDENCY_MISSING;

  /**
   * The lower-case name used in the evidence text and the audit trail.
   *
   * @return for example {@code "single_pay"}
   */
  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }
}

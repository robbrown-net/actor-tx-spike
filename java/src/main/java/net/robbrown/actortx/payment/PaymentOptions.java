package net.robbrown.actortx.payment;

/**
 * How a transaction behaves when providers misbehave.
 *
 * @param deadlineMs how long providers have to quote
 * @param stepTimeoutMs how long a provider has for each step
 * @param maxAttempts how often a failed step is tried before the provider is given up on
 * @param backoffMs the base wait before a retry (multiplied by the attempt number)
 * @param reconcileAttempts how often the provider is asked what it holds for an unanswered step
 * @param reconcileIntervalMs the wait between those questions
 * @param baseline the caller's fallback route when no quote is usable, or {@code null}
 */
public record PaymentOptions(
    long deadlineMs,
    long stepTimeoutMs,
    int maxAttempts,
    long backoffMs,
    int reconcileAttempts,
    long reconcileIntervalMs,
    String baseline) {

  /**
   * The defaults: 1 s to quote, 1 s per step, 2 attempts, 25 ms backoff, 3 reconciliations 50 ms
   * apart.
   */
  public static PaymentOptions defaults() {
    return new PaymentOptions(1_000, 1_000, 2, 25, 3, 50, null);
  }

  /** The same options with a different deadline for quotes. */
  public PaymentOptions withDeadlineMs(long ms) {
    return new PaymentOptions(
        ms,
        stepTimeoutMs,
        maxAttempts,
        backoffMs,
        reconcileAttempts,
        reconcileIntervalMs,
        baseline);
  }

  /** The same options with a different time allowed per step. */
  public PaymentOptions withStepTimeoutMs(long ms) {
    return new PaymentOptions(
        deadlineMs, ms, maxAttempts, backoffMs, reconcileAttempts, reconcileIntervalMs, baseline);
  }

  /** The same options with a different number of attempts. */
  public PaymentOptions withMaxAttempts(int attempts) {
    return new PaymentOptions(
        deadlineMs,
        stepTimeoutMs,
        attempts,
        backoffMs,
        reconcileAttempts,
        reconcileIntervalMs,
        baseline);
  }

  /** The same options with a different backoff. */
  public PaymentOptions withBackoffMs(long ms) {
    return new PaymentOptions(
        deadlineMs,
        stepTimeoutMs,
        maxAttempts,
        ms,
        reconcileAttempts,
        reconcileIntervalMs,
        baseline);
  }

  /** The same options with a different number of reconciliations. */
  public PaymentOptions withReconcileAttempts(int attempts) {
    return new PaymentOptions(
        deadlineMs, stepTimeoutMs, maxAttempts, backoffMs, attempts, reconcileIntervalMs, baseline);
  }

  /** The same options with a different reconciliation interval. */
  public PaymentOptions withReconcileIntervalMs(long ms) {
    return new PaymentOptions(
        deadlineMs, stepTimeoutMs, maxAttempts, backoffMs, reconcileAttempts, ms, baseline);
  }

  /** The same options with a baseline route. */
  public PaymentOptions withBaseline(String route) {
    return new PaymentOptions(
        deadlineMs,
        stepTimeoutMs,
        maxAttempts,
        backoffMs,
        reconcileAttempts,
        reconcileIntervalMs,
        route);
  }
}

package net.robbrown.actortx.review;

/**
 * A step sent again under its original key.
 *
 * @param op the step that was sent again
 * @param key the idempotency key it was sent under
 * @param result the provider's answer, as its short word
 */
public record RetryResult(String op, String key, String result) {}

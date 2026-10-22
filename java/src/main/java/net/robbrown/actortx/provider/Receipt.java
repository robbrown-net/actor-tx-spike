package net.robbrown.actortx.provider;

/**
 * What a provider holds for an effect that happened.
 *
 * @param provider who did it
 * @param op what was done
 * @param key the idempotency key it was done under
 * @param rate the quoted rate, when the operation carried a quote
 * @param fee the quoted fee, when the operation carried a quote
 */
public record Receipt(String provider, Operation op, String key, Long rate, Long fee) {}

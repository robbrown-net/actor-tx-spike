package net.robbrown.actortx.selection;

/**
 * A provider that did not make the ranking, and why.
 *
 * @param providerId who
 * @param reason a short machine-readable reason: {@code "quote_expires_too_soon"}, {@code
 *     "too_slow"}, {@code "timeout"}, {@code "rejected: no_liquidity"}, {@code "error: boom"},
 *     {@code "worse_than_best_bps: 12.3"}, {@code "state: stalled"}, {@code "unsupported: USD/INR"}
 */
public record Excluded(String providerId, String reason) {}

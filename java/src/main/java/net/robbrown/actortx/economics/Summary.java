package net.robbrown.actortx.economics;

import java.util.Map;

/**
 * The ledger's totals: overall, per provider, and the rate card they were priced with.
 *
 * @param sinceMs the wall-clock milliseconds when the ledger started counting
 * @param overall the figures over every transaction, with the platform cost
 * @param byProvider the figures per provider (no platform cost)
 * @param rateCard the rate card in force
 */
public record Summary(
    long sinceMs, Figures overall, Map<String, Figures> byProvider, RateCard rateCard) {}

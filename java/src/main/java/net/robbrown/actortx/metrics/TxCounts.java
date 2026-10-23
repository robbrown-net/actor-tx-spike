package net.robbrown.actortx.metrics;

/**
 * Transaction activity over one sample interval, and the number in flight at its end.
 *
 * @param started transactions received during the interval
 * @param completed transactions that completed during the interval
 * @param failed transactions that failed during the interval
 * @param needsReview transactions parked for review during the interval
 * @param failovers provider failovers during the interval
 * @param inFlight transactions received and not yet ended
 */
public record TxCounts(
    long started, long completed, long failed, long needsReview, long failovers, long inFlight) {}

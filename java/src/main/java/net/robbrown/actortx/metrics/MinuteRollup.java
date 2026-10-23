package net.robbrown.actortx.metrics;

/**
 * One minute of samples in compact form. Means and maxima are over the samples in the minute;
 * outcome counts are sums.
 *
 * @param at the first millisecond of the minute
 * @param samples how many samples the minute holds
 * @param heapUsedMean mean heap in use, in bytes
 * @param heapUsedMax largest heap in use, in bytes
 * @param threadsMax largest thread count
 * @param gcPerSecondMean mean collections per second
 * @param completed transactions completed in the minute
 * @param failed transactions failed in the minute
 * @param needsReview transactions parked for review in the minute
 * @param e2eP95Max the largest end-to-end 95th-percentile latency seen, in milliseconds
 */
public record MinuteRollup(
    long at,
    long samples,
    double heapUsedMean,
    double heapUsedMax,
    double threadsMax,
    double gcPerSecondMean,
    long completed,
    long failed,
    long needsReview,
    double e2eP95Max) {}

package net.robbrown.actortx.provider;

/**
 * Summary of the rolling window.
 *
 * @param samples calls in the window
 * @param errorRate share of calls that failed (error or timeout)
 * @param meanMs mean latency
 * @param p95Ms 95th-percentile latency
 */
public record Stats(int samples, double errorRate, double meanMs, double p95Ms) {}

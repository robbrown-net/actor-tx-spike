package net.robbrown.actortx.service;

import java.util.List;

/**
 * One provider as the dashboard shows it.
 *
 * @param id the provider
 * @param state our view of its health ({@code "healthy"}...)
 * @param breaker {@code "closed"}, {@code "open"} or {@code "half_open"}
 * @param errorRate share of recent calls that failed
 * @param p95Ms 95th percentile latency of recent calls
 * @param samples how many calls the figures cover
 * @param inFlight calls in flight now
 * @param version the version it last reported
 * @param recovering successes still needed to be fully healthy
 * @param rate the rate it quotes (display only)
 * @param fee the fee it quotes (display only)
 * @param latencyMs how long it takes
 * @param currencies what it carries
 */
public record ProviderView(
    String id,
    String state,
    String breaker,
    double errorRate,
    double p95Ms,
    int samples,
    int inFlight,
    String version,
    int recovering,
    double rate,
    double fee,
    long latencyMs,
    List<String> currencies,
    List<Fault> faults) {

  /** A misbehaviour in force: which operation, and what it does instead of behaving. */
  public record Fault(String op, String mode) {}
}

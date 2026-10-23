package net.robbrown.actortx.metrics;

import java.util.Map;

/**
 * One sample of the system: the JVM, the rates since the previous sample, the transactions and
 * their latency, and what each provider last reported.
 *
 * @param at wall-clock milliseconds when it was taken
 * @param jvm the JVM's figures
 * @param gcPerSecond collections per second since the previous sample
 * @param tx transaction activity since the previous sample
 * @param e2e end-to-end latency over the most recent transactions, in milliseconds
 * @param providers the latest health of each provider, by id
 * @param overheadUs microseconds spent building this sample
 */
public record Sample(
    long at,
    JvmStats jvm,
    long gcPerSecond,
    TxCounts tx,
    Stats.Summary e2e,
    Map<String, ProviderHealth> providers,
    long overheadUs) {}

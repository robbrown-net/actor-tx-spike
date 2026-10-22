package net.robbrown.actortx.economics;

/**
 * The raw usage of one transaction, as the orchestrator measures it. Kept as facts, so any cost can
 * be recomputed later under a different rate card.
 *
 * @param reductions CPU work of the transaction's process over its life
 * @param memoryBytes process memory at completion
 * @param durationMs how long the transaction ran
 * @param providerCalls provider calls: quotes, steps, retries, undos and status checks
 * @param bytesOut bytes sent to providers (the external term size of each call, a proxy)
 * @param bytesIn bytes received from providers (the same proxy)
 * @param stateChanges state changes, one per narrated event
 * @param compensations undo operations (measured, not priced)
 */
public record Measurement(
    long reductions,
    long memoryBytes,
    long durationMs,
    long providerCalls,
    long bytesOut,
    long bytesIn,
    long stateChanges,
    long compensations) {}

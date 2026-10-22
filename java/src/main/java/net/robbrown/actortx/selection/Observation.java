package net.robbrown.actortx.selection;

import net.robbrown.actortx.provider.Answer;
import net.robbrown.actortx.provider.HealthState;
import net.robbrown.actortx.provider.Quote;

/**
 * What one provider answered to a quote request, with the context needed to judge it again later:
 * its health state when asked, how long it took and when it was seen. Replay judges recorded
 * observations with the same code that judged them live.
 *
 * @param providerId who answered
 * @param state its health when asked
 * @param result what it answered
 * @param latencyMs how long it took
 * @param observedAtMs wall-clock milliseconds when it was seen
 */
public record Observation(
    String providerId,
    HealthState state,
    Answer<Quote> result,
    long latencyMs,
    long observedAtMs) {}

package net.robbrown.actortx.replay;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.robbrown.actortx.payment.Outcome;

/**
 * One transaction read back from its records: what was asked, how it ended, where the money went
 * and what it was worth. Fields that the log does not say are null.
 *
 * @param id the transaction
 * @param request the request as it was received
 * @param amount the amount asked, minor units
 * @param startedAtMs when the transaction was received
 * @param endedAtMs when its terminal record was written, or null while open
 * @param outcome how it ended, or empty while it is still open (no terminal record yet: in flight,
 *     or the node stopped)
 * @param phase the last phase the payment reported
 * @param provider the provider it ended with
 * @param steps the operations that succeeded at that provider, in order
 * @param net the net value of its quote at that provider, minor units
 * @param margin the margin recorded in its economics
 * @param resolution the outcome a person recorded in review, if any
 */
public record TxSummary(
    String id,
    Map<String, Object> request,
    Long amount,
    Long startedAtMs,
    Long endedAtMs,
    Optional<Outcome> outcome,
    String phase,
    String provider,
    List<String> steps,
    Long net,
    Long margin,
    String resolution) {}

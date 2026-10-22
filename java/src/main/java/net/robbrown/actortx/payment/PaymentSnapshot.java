package net.robbrown.actortx.payment;

import java.util.List;

/**
 * A transaction as it stands: for the dashboard and for the review queue.
 *
 * @param id the transaction
 * @param phase where it is
 * @param provider the provider it is using, or {@code null}
 * @param done the steps that succeeded at that provider, in order
 * @param history what happened, oldest first
 * @param result how it ended, or {@code null} while it runs
 */
public record PaymentSnapshot(
    String id,
    Phase phase,
    String provider,
    List<String> done,
    List<HistoryEntry> history,
    Result result) {

  /** One line of a transaction's story. */
  public record HistoryEntry(long atMs, Phase phase, String kind, String message) {}
}

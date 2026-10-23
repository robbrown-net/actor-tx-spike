package net.robbrown.actortx.service;

import java.util.List;
import net.robbrown.actortx.payment.PaymentSnapshot;
import net.robbrown.actortx.payment.Result;
import net.robbrown.actortx.payment.Result.Completed;
import net.robbrown.actortx.payment.Result.Failed;
import net.robbrown.actortx.payment.Result.NeedsReview;
import net.robbrown.actortx.payment.Result.Summary;

/**
 * A transaction as the API shows it.
 *
 * @param id the transaction
 * @param phase where it is
 * @param provider who is serving it now, or {@code null}
 * @param done the steps done at that provider
 * @param history what happened, in order
 * @param outcome how it ended, or {@code null} while it runs
 */
public record PaymentView(
    String id,
    String phase,
    String provider,
    List<String> done,
    List<Entry> history,
    Outcome outcome) {

  /** One thing that happened. */
  public record Entry(long atMs, String phase, String kind, String message) {}

  /**
   * How a transaction ended.
   *
   * @param status {@code completed}, {@code failed} or {@code needs_review}
   * @param reason why, unless it completed
   * @param summary the result, if it completed
   */
  public record Outcome(String status, String reason, Summary summary) {

    static Outcome of(Result result) {
      return switch (result) {
        case Completed completed ->
            new Outcome(completed.outcome().wire(), null, completed.summary());
        case Failed failed -> new Outcome(failed.outcome().wire(), failed.reason(), null);
        case NeedsReview review -> new Outcome(review.outcome().wire(), review.reason(), null);
      };
    }
  }

  /** The view of a snapshot. */
  public static PaymentView of(PaymentSnapshot snapshot) {
    return new PaymentView(
        snapshot.id(),
        snapshot.phase().wire(),
        snapshot.provider(),
        snapshot.done(),
        snapshot.history().stream()
            .map(h -> new Entry(h.atMs(), h.phase().wire(), h.kind(), h.message()))
            .toList(),
        snapshot.result() == null ? null : Outcome.of(snapshot.result()));
  }
}

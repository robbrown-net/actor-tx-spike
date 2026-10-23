package net.robbrown.actortx.review;

import java.util.List;
import net.robbrown.actortx.review.ReviewEntry.Resolution;

/** What a dashboard command returned, by the verb that was run. */
public sealed interface ReviewReply {

  /**
   * The result of a check.
   *
   * @param answers what each provider holds, per step
   */
  record Checked(List<CheckAnswer> answers) implements ReviewReply {}

  /**
   * The result of a retry.
   *
   * @param result the step, its key and the answer
   */
  record Retried(RetryResult result) implements ReviewReply {}

  /**
   * The result of a resolve.
   *
   * @param resolution the decision
   */
  record Resolved(Resolution resolution) implements ReviewReply {}
}

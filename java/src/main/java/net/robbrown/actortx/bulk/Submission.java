package net.robbrown.actortx.bulk;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** What a {@link Submitter} answered: accepted, refused as invalid, or refused as busy. */
public sealed interface Submission {

  /**
   * The payment was accepted.
   *
   * @param id the payment's id
   * @param ending completes when the payment has ended, with how it ended
   */
  record Accepted(String id, CompletableFuture<Ending> ending) implements Submission {}

  /**
   * The input was not valid.
   *
   * @param errors every problem found
   */
  record Invalid(List<String> errors) implements Submission {}

  /**
   * The system is at its admission limit. The same command is sent again after a pause.
   *
   * @param inFlight how many transactions are in flight
   * @param retryAfterMs the pause the system suggests before trying again
   */
  record Busy(int inFlight, long retryAfterMs) implements Submission {}
}

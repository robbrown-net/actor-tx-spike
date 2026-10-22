package net.robbrown.actortx.payment;

import java.util.Optional;

/** Regional settlement cut-offs: when the transaction's deadline falls, if it has one. */
@FunctionalInterface
public interface Deadlines {

  /** No transaction has a deadline. */
  Deadlines NONE = (request, nowMs) -> Optional.empty();

  /**
   * A deadline.
   *
   * @param region the region whose cut-off it is
   * @param atMs wall-clock milliseconds
   */
  record Deadline(String region, long atMs) {}

  /** The deadline of a request, looked up at {@code nowMs}. */
  Optional<Deadline> forRequest(TransactionRequest request, long nowMs);
}

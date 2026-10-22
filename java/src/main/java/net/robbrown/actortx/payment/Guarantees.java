package net.robbrown.actortx.payment;

import java.util.Optional;
import net.robbrown.actortx.payment.Result.GuaranteeOutcome;

/** Standing guarantees: judges a completed transaction against its merchant's guaranteed rate. */
@FunctionalInterface
public interface Guarantees {

  /** No merchant has a guarantee. */
  Guarantees NONE = (txId, request, achievedNet) -> Optional.empty();

  /**
   * Judge a completed transaction and record it in the guarantee ledger.
   *
   * @param txId the transaction
   * @param request what was asked
   * @param achievedNet what the customer got, minor units
   * @return the outcome, or empty if the merchant has no guarantee
   */
  Optional<GuaranteeOutcome> settle(String txId, TransactionRequest request, long achievedNet);
}

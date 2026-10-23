package net.robbrown.actortx.service;

import java.util.Map;
import java.util.Optional;
import net.robbrown.actortx.guarantee.Guarantee;
import net.robbrown.actortx.guarantee.GuaranteeLedger;
import net.robbrown.actortx.payment.Guarantees;
import net.robbrown.actortx.payment.Result.GuaranteeOutcome;
import net.robbrown.actortx.payment.TransactionRequest;

/** The merchants' standing guarantees: judges each completed payment and keeps the ledger. */
public final class GuaranteeBook implements Guarantees {

  private final Map<String, Guarantee> guarantees;
  private final GuaranteeLedger ledger;

  /** A book of guarantees by merchant, recording in {@code ledger}. */
  public GuaranteeBook(Map<String, Guarantee> guarantees, GuaranteeLedger ledger) {
    this.guarantees = Map.copyOf(guarantees);
    this.ledger = ledger;
  }

  /** The ledger of how the guarantees have fared. */
  public GuaranteeLedger ledger() {
    return ledger;
  }

  @Override
  public Optional<GuaranteeOutcome> settle(String txId, TransactionRequest request, long net) {
    return Optional.ofNullable(request.merchantId())
        .map(guarantees::get)
        .map(guarantee -> judge(txId, request, guarantee, net));
  }

  private GuaranteeOutcome judge(
      String txId, TransactionRequest request, Guarantee guarantee, long net) {
    Guarantee.Evaluation e = guarantee.evaluate(request.amount(), net);
    ledger.record(
        new GuaranteeLedger.Entry(
            txId,
            guarantee.merchantId(),
            guarantee.period(),
            e.guaranteedNet(),
            e.achievedNet(),
            e.margin()));
    return new GuaranteeOutcome(guarantee.merchantId(), guarantee.period(), e.margin());
  }
}

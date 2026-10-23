package net.robbrown.actortx.payment;

/** Chooses the workflow for a payment going through a provider. */
@FunctionalInterface
public interface Workflows {

  /** Every payment uses the standard workflow. */
  Workflows STANDARD = (request, providerId) -> Workflow.standard();

  /** The workflow for this request through this provider. */
  Workflow forRoute(TransactionRequest request, String providerId);
}

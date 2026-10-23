package net.robbrown.actortx.bulk;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import net.robbrown.actortx.payment.Outcome;
import net.robbrown.actortx.service.BusyException;
import net.robbrown.actortx.service.InvalidInputException;
import net.robbrown.actortx.service.PaymentCommand;
import net.robbrown.actortx.service.PaymentService;
import net.robbrown.actortx.service.PaymentView;

/** Sends bulk payments through the real payment service and reports how each one ends. */
public final class PaymentSubmitter implements Submitter {

  private static final Duration PATIENCE = Duration.ofMinutes(5);
  private static final long RETRY_AFTER_MS = 1_000;

  private final PaymentService payments;

  /** A submitter over {@code payments}. */
  public PaymentSubmitter(PaymentService payments) {
    this.payments = payments;
  }

  @Override
  public Submission submit(PaymentCommand command) {
    try {
      String id = payments.submit(command);
      return new Submission.Accepted(id, ending(id));
    } catch (InvalidInputException invalid) {
      return new Submission.Invalid(invalid.errors());
    } catch (BusyException busy) {
      return new Submission.Busy(payments.inFlight(), RETRY_AFTER_MS);
    }
  }

  // Completed by a virtual thread that waits for the payment to end.
  private CompletableFuture<Ending> ending(String id) {
    CompletableFuture<Ending> ending = new CompletableFuture<>();
    Thread.ofVirtual()
        .start(
            () ->
                ending.complete(
                    payments
                        .await(id, PATIENCE)
                        .map(view -> endingOf(view))
                        .orElseGet(() -> new Ending(Outcome.FAILED, "still_running"))));
    return ending;
  }

  // The wire name is one Result.outcome() wrote, so it always names an outcome.
  private static Ending endingOf(PaymentView view) {
    PaymentView.Outcome outcome = view.outcome();
    return new Ending(Outcome.fromWire(outcome.status()).orElse(Outcome.FAILED), outcome.reason());
  }
}

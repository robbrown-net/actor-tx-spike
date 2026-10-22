package net.robbrown.actortx.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import net.robbrown.actortx.money.Money;
import net.robbrown.actortx.payment.Payment;
import net.robbrown.actortx.payment.Payment.Routing;
import net.robbrown.actortx.payment.PaymentOptions;
import net.robbrown.actortx.payment.PaymentServices;
import net.robbrown.actortx.payment.TransactionRequest;
import net.robbrown.actortx.policy.PolicySet;
import net.robbrown.actortx.policy.PolicySet.Resolved;

/**
 * Sends payments: validates a request, resolves its policy, admits it if there is room, and runs
 * it. Finished transactions are kept for a while so they can still be looked at.
 */
public class PaymentService {

  private static final int KEPT = 10_000;

  private final ProviderCatalog catalog;
  private final EligibilityService eligibility;
  private final PolicyResolver resolver;
  private final PaymentOptions options;
  private final PaymentServices services;
  private final Admission admission;
  private final AtomicLong ids = new AtomicLong();
  private final Map<String, Payment> payments =
      Collections.synchronizedMap(
          new LinkedHashMap<>() {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Payment> eldest) {
              return size() > KEPT;
            }
          });

  /**
   * A service over the catalog.
   *
   * @param services selection, events and the write-ahead barrier; its {@code ended} hook is
   *     replaced by the admission release
   */
  public PaymentService(
      ProviderCatalog catalog,
      EligibilityService eligibility,
      PolicyResolver resolver,
      PaymentOptions options,
      PaymentServices services,
      Admission admission) {
    this.catalog = catalog;
    this.eligibility = eligibility;
    this.resolver = resolver;
    this.options = options;
    this.services = services.withEnded(id -> admission.release());
    this.admission = admission;
  }

  /**
   * Start a payment.
   *
   * @return its id
   * @throws InvalidInputException when the request is not valid
   * @throws BusyException when the system is at its admission limit
   */
  public String submit(PaymentCommand command) {
    TransactionRequest request = requestOf(command);
    Resolved resolved = resolver.resolve(command.merchantId(), command.customerId());
    if (!admission.tryAdmit()) {
      throw new BusyException(admission.inFlight());
    }
    Routing routing =
        new Routing(
            catalog.participants(),
            eligibility.rules(),
            resolved.policy(),
            PolicySet.describe(resolved.layers()));
    PaymentOptions chosen =
        command.baseline() == null ? options : options.withBaseline(command.baseline());
    payments.put(request.id(), new Payment(request, routing, chosen, services).start());
    return request.id();
  }

  /** The transaction as it stands, if it is known. */
  public Optional<PaymentView> view(String id) {
    return find(id).map(p -> PaymentView.of(p.snapshot()));
  }

  /** Wait for a transaction to end; empty if it is unknown or did not end in time. */
  public Optional<PaymentView> await(String id, Duration timeout) {
    return find(id).flatMap(p -> p.await(timeout).map(r -> PaymentView.of(p.snapshot())));
  }

  /** How many transactions are in flight. */
  public int inFlight() {
    return admission.inFlight();
  }

  private Optional<Payment> find(String id) {
    return Optional.ofNullable(payments.get(id));
  }

  private TransactionRequest requestOf(PaymentCommand command) {
    List<String> errors = new ArrayList<>();
    long amount = amountOf(command.amount(), errors);
    if (!errors.isEmpty()) {
      throw new InvalidInputException(errors);
    }
    return TransactionRequest.builder(
            "tx-" + Long.toString(ids.incrementAndGet() + System.nanoTime(), 36), amount)
        .merchant(command.merchantId())
        .customer(command.customerId())
        .origin(command.originRegion())
        .destination(command.destinationRegion())
        .instrument(command.instrument())
        .customerType(command.customerType())
        .from(command.sourceCurrency())
        .to(command.destinationCurrency())
        .build();
  }

  private static long amountOf(BigDecimal amount, List<String> errors) {
    if (amount == null || amount.signum() <= 0) {
      errors.add("amount: must be a positive number");
      return 0;
    }
    return Money.minor(amount.toPlainString());
  }
}

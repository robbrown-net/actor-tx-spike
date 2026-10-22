package net.robbrown.actortx.service;

import java.util.List;
import java.util.Map;
import net.robbrown.actortx.money.Money;
import net.robbrown.actortx.policy.Policy;
import net.robbrown.actortx.policy.PolicySet;
import net.robbrown.actortx.policy.PolicySet.Resolved;
import net.robbrown.actortx.provider.QuoteRequest;
import net.robbrown.actortx.selection.QuoteSelector;
import net.robbrown.actortx.selection.Selection;
import net.robbrown.actortx.validation.Validated;

/** Quote and select across the catalog's providers, under a policy given as plain data. */
public class SelectionService {

  private final ProviderCatalog catalog;
  private final QuoteSelector selector;
  private final PolicyResolver resolver;

  /**
   * A service over {@code catalog}, selecting with {@code selector}.
   *
   * @param resolver the merchant/customer cascade, or {@code null} to use the default policy
   */
  public SelectionService(
      ProviderCatalog catalog, QuoteSelector selector, PolicyResolver resolver) {
    this.catalog = catalog;
    this.selector = selector;
    this.resolver = resolver;
  }

  /**
   * Quote and select.
   *
   * @throws InvalidInputException when the policy is not valid
   */
  public SelectionView select(SelectCommand command) {
    Resolved resolved = policyOf(command);
    QuoteRequest request =
        new QuoteRequest(
            "sel-" + Long.toString(System.nanoTime(), 36),
            command.amount() == null ? 0L : Money.minor(command.amount().toPlainString()),
            command.sourceCurrency(),
            command.destinationCurrency());
    long deadline =
        command.deadlineMs() == null ? QuoteSelector.DEFAULT_DEADLINE_MS : command.deadlineMs();
    Selection selection =
        selector.select(
            request, catalog.participants(), resolved.policy(), deadline, command.baseline());
    return SelectionView.of(selection, PolicySet.describe(resolved.layers()));
  }

  // An explicit policy replaces the cascade; otherwise the merchant and customer layers apply.
  private Resolved policyOf(SelectCommand command) {
    if (command.policy() != null) {
      return new Resolved(explicit(command.policy()), List.of(new PolicySet.Layer.Global()));
    }
    if (resolver == null) {
      return new Resolved(Policy.defaults(), List.of(new PolicySet.Layer.Global()));
    }
    return resolver.resolve(command.merchantId(), command.customerId());
  }

  private static Policy explicit(Map<String, Object> data) {
    return switch (Policy.fromMap(data)) {
      case Validated.Valid<Policy> valid -> valid.value();
      case Validated.Invalid<Policy> invalid -> throw new InvalidInputException(invalid.errors());
    };
  }
}

package net.robbrown.actortx.payment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.robbrown.actortx.eligibility.Decision;
import net.robbrown.actortx.eligibility.Eligibility;
import net.robbrown.actortx.selection.Participant;

/** The records a replay reads: what a transaction was given, in the shape the audit log keeps. */
final class PaymentJournal {

  private PaymentJournal() {}

  /** The data of {@code tx_received}: the request, the policy, the settings and the decision. */
  static Map<String, Object> received(
      TransactionRequest request,
      Map<String, Object> policy,
      PaymentOptions options,
      List<Participant> eligible,
      List<Participant> denied,
      Decision decision) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("amount", request.amount());
    data.put("request", request.toMap());
    data.put("policy", policy);
    data.put("config", config(options));
    data.put("providers", eligible.stream().map(Participant::id).toList());
    data.put("eligibility", eligibility(decision, eligible, denied));
    return data;
  }

  private static Map<String, Object> config(PaymentOptions options) {
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("deadline_ms", options.deadlineMs());
    config.put("step_timeout_ms", options.stepTimeoutMs());
    config.put("max_attempts", options.maxAttempts());
    config.put("backoff_ms", options.backoffMs());
    config.put("reconcile_attempts", options.reconcileAttempts());
    config.put("reconcile_interval_ms", options.reconcileIntervalMs());
    return config;
  }

  // The decision as recorded: what applied, and who was ruled out and why.
  private static Map<String, Object> eligibility(
      Decision decision, List<Participant> eligible, List<Participant> denied) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("permitted", decision.permitted());
    map.put("reasons", decision.reasons());
    map.put("regions", decision.regions());
    map.put(
        "instrument",
        decision.instrument() == null
            ? null
            : decision.instrument().name().toLowerCase(java.util.Locale.ROOT));
    map.put(
        "customer_type",
        decision.customerType() == null
            ? null
            : decision.customerType().name().toLowerCase(java.util.Locale.ROOT));
    map.put("source_currency", decision.sourceCurrency());
    map.put("destination_currency", decision.destinationCurrency());
    map.put("logs", decision.logs());
    map.put("retention_days", decision.retentionDays());
    map.put("allowed_providers", eligible.stream().map(Participant::id).toList());
    map.put("denied_providers", denied.stream().map(p -> denial(decision, p)).toList());
    return map;
  }

  private static Map<String, Object> denial(Decision decision, Participant provider) {
    return Map.of(
        "provider", provider.id(), "reason", Eligibility.denialReason(decision, provider.id()));
  }
}

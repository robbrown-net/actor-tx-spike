package net.robbrown.actortx.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.robbrown.actortx.economics.EconomicsLedger;
import net.robbrown.actortx.guarantee.GuaranteeLedger;

/** The one picture of the system the dashboard polls: providers, policy, cut-offs, ledgers. */
public final class SnapshotService {

  private static final List<Map<String, Object>> MERCHANTS =
      List.of(
          Map.of("id", "acme", "entitled", true, "guarantee", 1.078),
          Map.of("id", "globex", "entitled", false));

  private final ProviderCatalog catalog;
  private final PolicyResolver policies;
  private final CutoffBook cutoffs;
  private final GuaranteeLedger guarantees;
  private final EconomicsLedger economics;
  private final Admission admission;
  private final PaymentService payments;

  /** Gathers the pieces. */
  public SnapshotService(
      ProviderCatalog catalog,
      PolicyResolver policies,
      CutoffBook cutoffs,
      Ledgers ledgers,
      Admission admission,
      PaymentService payments) {
    this.catalog = catalog;
    this.policies = policies;
    this.cutoffs = cutoffs;
    this.guarantees = ledgers.guarantees();
    this.economics = ledgers.economics();
    this.admission = admission;
    this.payments = payments;
  }

  /** Both ledgers, handed over together. */
  public record Ledgers(GuaranteeLedger guarantees, EconomicsLedger economics) {}

  /** The picture, in the shape the dashboard reads. */
  public Map<String, Object> snapshot() {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("providers", catalog.views());
    snapshot.put("policy", policies.resolve(null, null).policy().toMap());
    snapshot.put("merchants", MERCHANTS);
    snapshot.put("customers", List.of(Map.of("id", "vip-1")));
    snapshot.put("cutoffs", cutoffList());
    snapshot.put("ledger", guarantees.all());
    snapshot.put("economics", economics.summary());
    snapshot.put("admission", admission.stats());
    snapshot.put("auto", false);
    snapshot.put("pace", 1.0);
    snapshot.put("submitted", payments.submitted());
    return snapshot;
  }

  private List<Map<String, Object>> cutoffList() {
    return cutoffs.remaining(System.currentTimeMillis()).entrySet().stream()
        .<Map<String, Object>>map(e -> Map.of("region", e.getKey(), "remaining_ms", e.getValue()))
        .toList();
  }
}

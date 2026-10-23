package net.robbrown.actortx.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import net.robbrown.actortx.economics.EconomicsLedger;
import net.robbrown.actortx.economics.RateCard;
import net.robbrown.actortx.eligibility.Eligibility;
import net.robbrown.actortx.events.EventSink;
import net.robbrown.actortx.guarantee.GuaranteeLedger;
import net.robbrown.actortx.payment.PaymentOptions;
import net.robbrown.actortx.payment.PaymentServices;
import net.robbrown.actortx.policy.PolicySet;
import net.robbrown.actortx.provider.FakeProvider;
import net.robbrown.actortx.selection.QuoteSelector;
import net.robbrown.actortx.validation.Validated;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SnapshotServiceTest {

  private final StubCatalog catalog = new StubCatalog(new FakeProvider("p"));
  private final QuoteSelector selector = new QuoteSelector(EventSink.NONE);
  private final Admission admission = new Admission(9);
  private final PolicyResolver resolver =
      new PolicyResolver(((Validated.Valid<PolicySet>) PolicySet.fromMap(Map.of())).value());

  @AfterEach
  void tearDown() {
    selector.close();
    catalog.close();
  }

  private PaymentService payments() {
    return new PaymentService(
        catalog,
        new EligibilityService(Eligibility.open()),
        resolver,
        PaymentOptions.defaults(),
        PaymentServices.of(selector, EventSink.NONE),
        admission);
  }

  @Test
  void theSnapshotHasEverythingTheDashboardReads() {
    CutoffBook cutoffs = new CutoffBook();
    cutoffs.set("IN", System.currentTimeMillis() + 60_000);
    SnapshotService.Ledgers ledgers =
        new SnapshotService.Ledgers(
            new GuaranteeLedger(), new EconomicsLedger(RateCard.defaults()));

    Map<String, Object> snapshot =
        new SnapshotService(catalog, resolver, cutoffs, ledgers, admission, payments()).snapshot();

    assertThat(snapshot)
        .containsKeys(
            "providers", "policy", "merchants", "customers", "cutoffs", "ledger", "economics")
        .containsKeys("admission", "auto", "pace", "submitted");
    assertThat(snapshot.get("cutoffs").toString()).contains("IN");
    assertThat(snapshot.get("submitted")).isEqualTo(0L);
  }
}

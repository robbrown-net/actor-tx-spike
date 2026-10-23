package net.robbrown.actortx.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.robbrown.actortx.eligibility.Eligibility;
import net.robbrown.actortx.events.EventSink;
import net.robbrown.actortx.payment.PaymentOptions;
import net.robbrown.actortx.payment.PaymentServices;
import net.robbrown.actortx.policy.PolicySet;
import net.robbrown.actortx.provider.FakeProvider;
import net.robbrown.actortx.review.ReviewQueue;
import net.robbrown.actortx.review.ReviewService;
import net.robbrown.actortx.selection.QuoteSelector;
import net.robbrown.actortx.validation.Validated;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ControlActionsTest {

  private final List<String> events = new ArrayList<>();
  private final EventSink sink = (tx, actor, kind, message, data) -> events.add(kind);
  private StubCatalog catalog;
  private QuoteSelector selector;
  private Admission admission;
  private PaymentService payments;

  @BeforeEach
  void setUp() {
    catalog = new StubCatalog(new FakeProvider("best").rate("1.10"));
    selector = new QuoteSelector(EventSink.NONE);
    admission = new Admission(5);
    payments =
        new PaymentService(
            catalog,
            new EligibilityService(Eligibility.open()),
            new PolicyResolver(((Validated.Valid<PolicySet>) PolicySet.fromMap(Map.of())).value()),
            PaymentOptions.defaults(),
            PaymentServices.of(selector, EventSink.NONE),
            admission);
  }

  @AfterEach
  void tearDown() {
    selector.close();
    catalog.close();
  }

  @Test
  void aProviderCommandIsPassedToTheControl() {
    ControlAction action =
        ControlActions.provider((id, preset, params) -> Map.of("provider", id, "change", preset));

    assertThat(action.name()).isEqualTo("provider");
    assertThat(action.apply(Map.of("id", "northwind", "do", "slow")))
        .containsEntry("provider", "northwind")
        .containsEntry("change", "slow");
  }

  @Test
  void aCutoffIsSetAnnouncedAndCleared() {
    CutoffBook cutoffs = new CutoffBook();
    ControlAction action = ControlActions.cutoff(cutoffs, sink);

    assertThat(action.apply(Map.of("region", "IN", "in_seconds", 60)))
        .containsEntry("in_seconds", 60.0);
    assertThat(cutoffs.remaining(System.currentTimeMillis())).containsKey("IN");
    assertThat(events).containsExactly("cutoff_set");
    assertThat(action.apply(Map.of("region", "IN"))).containsEntry("cleared", true);
    assertThat(cutoffs.remaining(System.currentTimeMillis())).isEmpty();
  }

  @Test
  void submittingSendsAPaymentFromTheDashboardsFieldNames() {
    ControlAction action = ControlActions.submit(payments);

    Map<String, Object> result =
        action.apply(
            Map.of("amount", 250, "merchant", "acme", "region", "", "customer_type", "retail"));

    assertThat(result.get("id")).isNotNull();
    assertThat(payments.view((String) result.get("id"))).isPresent();
    assertThat(ControlActions.commandOf(Map.of()).amount()).isEqualByComparingTo("1000");
  }

  @Test
  void theAdmissionLimitIsChangedOrRemoved() {
    ControlAction action = ControlActions.admission(admission, sink);

    assertThat(action.apply(Map.of("max_in_flight", 3))).containsEntry("limit", 3);
    assertThat(action.apply(Map.of("max_in_flight", "7"))).containsEntry("limit", 7);
    assertThat(action.apply(Map.of("max_in_flight", "none"))).containsEntry("limit", null);
    assertThat(action.apply(Map.of())).containsEntry("limit", null);
    assertThat(events).containsOnly("admission_changed");
    assertThatThrownBy(() -> action.apply(Map.of("max_in_flight", "lots")))
        .isInstanceOf(InvalidInputException.class);
  }

  @Test
  void reviewCommandsAreHandedToTheReviewService() {
    ReviewService reviews =
        new ReviewService(
            new ReviewQueue(),
            new ServerReviewProviders(catalog),
            sink,
            java.util.Optional::empty,
            System::currentTimeMillis);

    assertThat(ControlActions.review(reviews).name()).isEqualTo("review");
    assertThatThrownBy(() -> ControlActions.review(reviews).apply(Map.of("do", "nonsense")))
        .isInstanceOf(InvalidInputException.class);
  }
}

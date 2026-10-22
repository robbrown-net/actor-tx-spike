package net.robbrown.actortx.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import net.robbrown.actortx.eligibility.Eligibility;
import net.robbrown.actortx.events.EventSink;
import net.robbrown.actortx.payment.PaymentOptions;
import net.robbrown.actortx.payment.PaymentServices;
import net.robbrown.actortx.policy.PolicySet;
import net.robbrown.actortx.provider.FakeProvider;
import net.robbrown.actortx.selection.QuoteSelector;
import net.robbrown.actortx.validation.Validated;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PaymentServiceTest {

  private StubCatalog catalog;
  private QuoteSelector selector;
  private Admission admission;
  private PaymentService service;

  @BeforeEach
  void setUp() {
    catalog =
        new StubCatalog(
            new FakeProvider("cheap").rate("1.00"), new FakeProvider("best").rate("1.10"));
    selector = new QuoteSelector(EventSink.NONE);
    admission = new Admission(1);
    service =
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

  private static PaymentCommand pay(BigDecimal amount, String baseline) {
    return new PaymentCommand(amount, null, null, null, null, null, null, null, null, baseline);
  }

  @Test
  void aPaymentRunsToCompletionAndFreesItsPlace() {
    String id = service.submit(pay(new BigDecimal("100.00"), null));

    PaymentView view = service.await(id, Duration.ofSeconds(10)).orElseThrow();

    assertThat(view.outcome().status()).isEqualTo("completed");
    assertThat(view.outcome().summary().provider()).isEqualTo("best");
    assertThat(view.phase()).isEqualTo("completed");
    assertThat(view.history()).isNotEmpty();
    assertThat(service.view(id)).isPresent();
    assertThat(service.inFlight()).isZero();
  }

  @Test
  void aBaselineIsPassedToSelection() {
    String id = service.submit(pay(new BigDecimal("1.00"), "wire"));

    assertThat(service.await(id, Duration.ofSeconds(10))).isPresent();
  }

  @Test
  void aSecondPaymentIsTurnedAwayWhileTheFirstIsInFlight() {
    assertThat(admission.tryAdmit()).isTrue();

    assertThatThrownBy(() -> service.submit(pay(new BigDecimal("5"), null)))
        .isInstanceOf(BusyException.class);
  }

  @Test
  void anInvalidAmountIsRefusedAndNothingIsAdmitted() {
    assertThatThrownBy(() -> service.submit(pay(null, null)))
        .isInstanceOf(InvalidInputException.class)
        .hasMessage("amount: must be a positive number");
    assertThatThrownBy(() -> service.submit(pay(BigDecimal.ZERO, null)))
        .isInstanceOf(InvalidInputException.class);
    assertThat(service.inFlight()).isZero();
  }

  @Test
  void anUnknownTransactionIsNotFound() {
    assertThat(service.view("nope")).isEmpty();
    assertThat(service.await("nope", Duration.ofMillis(5))).isEmpty();
  }
}

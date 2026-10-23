package net.robbrown.actortx.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.robbrown.actortx.bulk.Ending.Status;
import net.robbrown.actortx.eligibility.Eligibility;
import net.robbrown.actortx.events.EventSink;
import net.robbrown.actortx.payment.PaymentOptions;
import net.robbrown.actortx.payment.PaymentServices;
import net.robbrown.actortx.policy.PolicySet;
import net.robbrown.actortx.provider.FakeProvider;
import net.robbrown.actortx.provider.Operation;
import net.robbrown.actortx.selection.QuoteSelector;
import net.robbrown.actortx.service.Admission;
import net.robbrown.actortx.service.EligibilityService;
import net.robbrown.actortx.service.PaymentCommand;
import net.robbrown.actortx.service.PaymentService;
import net.robbrown.actortx.service.PolicyResolver;
import net.robbrown.actortx.service.StubCatalog;
import net.robbrown.actortx.validation.Validated;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PaymentSubmitterTest {

  private FakeProvider fake;
  private StubCatalog catalog;
  private QuoteSelector selector;
  private Admission admission;
  private PaymentSubmitter submitter;

  @BeforeEach
  void setUp() {
    fake = new FakeProvider("p").rate("1.1");
    catalog = new StubCatalog(fake);
    selector = new QuoteSelector(EventSink.NONE);
    admission = new Admission(1);
    PaymentService payments =
        new PaymentService(
            catalog,
            new EligibilityService(Eligibility.open()),
            new PolicyResolver(((Validated.Valid<PolicySet>) PolicySet.fromMap(Map.of())).value()),
            PaymentOptions.defaults().withStepTimeoutMs(300).withBackoffMs(1),
            PaymentServices.of(selector, EventSink.NONE),
            admission);
    submitter = new PaymentSubmitter(payments);
  }

  @AfterEach
  void tearDown() {
    selector.close();
    catalog.close();
  }

  private static PaymentCommand pay(BigDecimal amount) {
    return new PaymentCommand(amount, null, null, null, null, null, null, null, null, null);
  }

  @Test
  void anAcceptedPaymentReportsHowItEnds() throws Exception {
    Submission result = submitter.submit(pay(new BigDecimal("50")));

    Ending ending = ((Submission.Accepted) result).ending().get(10, TimeUnit.SECONDS);

    assertThat(ending.status()).isEqualTo(Status.COMPLETED);
  }

  @Test
  void aPaymentThatNeedsAPersonSaysSo() throws Exception {
    fake.script(Operation.SETTLE, net.robbrown.actortx.provider.Mode.REJECT);

    Ending ending =
        ((Submission.Accepted) submitter.submit(pay(new BigDecimal("50"))))
            .ending()
            .get(10, TimeUnit.SECONDS);

    assertThat(ending.status()).isEqualTo(Status.NEEDS_REVIEW);
    assertThat(ending.reason()).startsWith("settlement_failed");
  }

  @Test
  void aBadAmountIsInvalidAndAFullHouseIsBusy() {
    assertThat(submitter.submit(pay(null))).isInstanceOf(Submission.Invalid.class);

    admission.tryAdmit();

    assertThat(submitter.submit(pay(new BigDecimal("5")))).isInstanceOf(Submission.Busy.class);
  }
}

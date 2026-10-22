package net.robbrown.actortx.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PaymentTypesTest {

  @Test
  void optionsAreChangedOneFieldAtATime() {
    PaymentOptions o =
        PaymentOptions.defaults()
            .withDeadlineMs(1)
            .withStepTimeoutMs(2)
            .withMaxAttempts(3)
            .withBackoffMs(4)
            .withReconcileAttempts(5)
            .withReconcileIntervalMs(6)
            .withBaseline("wire");

    assertThat(o).isEqualTo(new PaymentOptions(1, 2, 3, 4, 5, 6, "wire"));
  }

  @Test
  void aRequestIsRecordedWithOnlyWhatWasGiven() {
    TransactionRequest bare = TransactionRequest.of("t", 5);
    TransactionRequest full =
        TransactionRequest.builder("t", 5)
            .merchant("m")
            .customer("c")
            .origin("US")
            .destination("IN")
            .instrument("forward")
            .customerType("retail")
            .from("USD")
            .to("INR")
            .build();

    assertThat(bare.toMap()).isEqualTo(Map.of("id", "t", "amount", 5L));
    assertThat(full.toMap())
        .containsEntry("merchant_id", "m")
        .containsEntry("customer_id", "c")
        .containsEntry("origin_region", "US")
        .containsEntry("destination_region", "IN")
        .containsEntry("instrument", "forward")
        .containsEntry("customer_type", "retail")
        .containsEntry("source_currency", "USD")
        .containsEntry("destination_currency", "INR");
    assertThat(full.eligibilityMap()).containsEntry("originRegion", "US");
    assertThat(List.of(Phase.RECEIVED, Phase.COMPLETED).stream().map(Phase::terminal))
        .containsExactly(false, true);
  }
}

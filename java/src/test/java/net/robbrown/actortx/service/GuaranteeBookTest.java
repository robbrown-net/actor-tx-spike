package net.robbrown.actortx.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import net.robbrown.actortx.guarantee.Guarantee;
import net.robbrown.actortx.guarantee.GuaranteeLedger;
import net.robbrown.actortx.payment.TransactionRequest;
import net.robbrown.actortx.validation.Validated;
import org.junit.jupiter.api.Test;

class GuaranteeBookTest {

  private final GuaranteeLedger ledger = new GuaranteeLedger();
  private final GuaranteeBook book =
      new GuaranteeBook(
          Map.of(
              "acme",
              ((Validated.Valid<Guarantee>)
                      Guarantee.fromMap(
                          Map.of("merchant_id", "acme", "period", "2026-W42", "rate", "1.10")))
                  .value()),
          ledger);

  @Test
  void aGuaranteedMerchantIsJudgedAndRecorded() {
    TransactionRequest request = TransactionRequest.builder("t", 100_000).merchant("acme").build();

    var outcome = book.settle("t", request, 105_000).orElseThrow();

    assertThat(outcome.merchantId()).isEqualTo("acme");
    assertThat(outcome.period()).isEqualTo("2026-W42");
    assertThat(outcome.margin()).isEqualTo(-5_000);
    assertThat(ledger.summary("acme", "2026-W42").count()).isEqualTo(1);
    assertThat(book.ledger()).isSameAs(ledger);
  }

  @Test
  void aMerchantWithoutAGuaranteeIsNotJudged() {
    assertThat(book.settle("t", TransactionRequest.builder("t", 1).merchant("other").build(), 1))
        .isEmpty();
    assertThat(book.settle("t", TransactionRequest.of("t", 1), 1)).isEmpty();
  }
}

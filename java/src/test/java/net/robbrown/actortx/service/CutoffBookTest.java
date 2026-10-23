package net.robbrown.actortx.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import net.robbrown.actortx.payment.TransactionRequest;
import org.junit.jupiter.api.Test;

class CutoffBookTest {

  private static TransactionRequest to(String region) {
    return TransactionRequest.builder("t", 1).destination(region).build();
  }

  @Test
  void aPaymentToARegionWithACutoffIsHeldToIt() {
    CutoffBook book = new CutoffBook();
    book.set("IN", 5_000);

    assertThat(book.forRequest(to("IN"), 1_000))
        .hasValueSatisfying(
            d -> {
              assertThat(d.region()).isEqualTo("IN");
              assertThat(d.atMs()).isEqualTo(5_000);
            });
    assertThat(book.remaining(1_000)).isEqualTo(Map.of("IN", 4_000L));
  }

  @Test
  void otherRegionsAndUnnamedDestinationsHaveNoDeadline() {
    CutoffBook book = new CutoffBook();
    book.set("IN", 5_000);

    assertThat(book.forRequest(to("US"), 1_000)).isEmpty();
    assertThat(book.forRequest(TransactionRequest.of("t", 1), 1_000)).isEmpty();
  }

  @Test
  void aClearedCutoffIsGone() {
    CutoffBook book = new CutoffBook();
    book.set("IN", 5_000);
    book.clear("IN");

    assertThat(book.forRequest(to("IN"), 1_000)).isEmpty();
  }
}

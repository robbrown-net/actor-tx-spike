package net.robbrown.actortx.provider;

/**
 * What a provider is asked to price.
 *
 * @param id the transaction id
 * @param amount minor units, or {@code null} if unspecified
 * @param sourceCurrency the currency paid in, or {@code null}
 * @param destinationCurrency the currency paid out, or {@code null}
 */
public record QuoteRequest(
    String id, Long amount, String sourceCurrency, String destinationCurrency) {

  /** A request with no currencies. */
  public static QuoteRequest of(String id, Long amount) {
    return new QuoteRequest(id, amount, null, null);
  }

  /** {@code "USD/EUR"} when both currencies are named, else {@code null}. */
  public String pair() {
    return sourceCurrency == null || destinationCurrency == null
        ? null
        : sourceCurrency + "/" + destinationCurrency;
  }
}

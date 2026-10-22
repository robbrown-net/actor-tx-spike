package net.robbrown.actortx.payment;

import java.util.LinkedHashMap;
import java.util.Map;
import net.robbrown.actortx.provider.QuoteRequest;

/**
 * What a merchant asks for: convert and pay an amount, optionally naming who, where and in which
 * currencies. Transaction ids must not contain {@code ':'} (they prefix idempotency keys).
 *
 * @param id the transaction id
 * @param amount minor units
 * @param merchantId the merchant, or {@code null}
 * @param customerId the customer, or {@code null}
 * @param originRegion where the money comes from, or {@code null}
 * @param destinationRegion where it goes, or {@code null}
 * @param instrument {@code "spot"} or {@code "forward"}, or {@code null} for spot
 * @param customerType {@code "business"} or {@code "retail"}, or {@code null} for business
 * @param sourceCurrency the currency paid in, or {@code null}
 * @param destinationCurrency the currency paid out, or {@code null}
 */
public record TransactionRequest(
    String id,
    long amount,
    String merchantId,
    String customerId,
    String originRegion,
    String destinationRegion,
    String instrument,
    String customerType,
    String sourceCurrency,
    String destinationCurrency) {

  /** A request for an amount with nothing else named. */
  public static TransactionRequest of(String id, long amount) {
    return builder(id, amount).build();
  }

  /** A builder for a request. */
  public static Builder builder(String id, long amount) {
    return new Builder(id, amount);
  }

  /** What the quote request to a provider carries. */
  public QuoteRequest quoteRequest() {
    return new QuoteRequest(id, amount, sourceCurrency, destinationCurrency);
  }

  /** The request as jurisdiction rules read it (camelCase keys, nulls left out). */
  public Map<String, Object> eligibilityMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    putIfPresent(map, "originRegion", originRegion);
    putIfPresent(map, "destinationRegion", destinationRegion);
    putIfPresent(map, "instrument", instrument);
    putIfPresent(map, "customerType", customerType);
    putIfPresent(map, "sourceCurrency", sourceCurrency);
    putIfPresent(map, "destinationCurrency", destinationCurrency);
    return map;
  }

  /** The request as the audit log records it (snake_case keys, nulls left out). */
  public Map<String, Object> toMap() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", id);
    map.put("amount", amount);
    putIfPresent(map, "merchant_id", merchantId);
    putIfPresent(map, "customer_id", customerId);
    putIfPresent(map, "origin_region", originRegion);
    putIfPresent(map, "destination_region", destinationRegion);
    putIfPresent(map, "instrument", instrument);
    putIfPresent(map, "customer_type", customerType);
    putIfPresent(map, "source_currency", sourceCurrency);
    putIfPresent(map, "destination_currency", destinationCurrency);
    return map;
  }

  private static void putIfPresent(Map<String, Object> map, String key, String value) {
    if (value != null) {
      map.put(key, value);
    }
  }

  /** Builds a {@link TransactionRequest}. */
  public static final class Builder {
    private final String id;
    private final long amount;
    private final String[] named = new String[8];

    private Builder(String id, long amount) {
      this.id = id;
      this.amount = amount;
    }

    private Builder set(int slot, String value) {
      named[slot] = value;
      return this;
    }

    /** The merchant. */
    public Builder merchant(String merchantId) {
      return set(0, merchantId);
    }

    /** The customer. */
    public Builder customer(String customerId) {
      return set(1, customerId);
    }

    /** Where the money comes from. */
    public Builder origin(String region) {
      return set(2, region);
    }

    /** Where the money goes. */
    public Builder destination(String region) {
      return set(3, region);
    }

    /** {@code "spot"} or {@code "forward"}. */
    public Builder instrument(String value) {
      return set(4, value);
    }

    /** {@code "business"} or {@code "retail"}. */
    public Builder customerType(String value) {
      return set(5, value);
    }

    /** The currency paid in. */
    public Builder from(String currency) {
      return set(6, currency);
    }

    /** The currency paid out. */
    public Builder to(String currency) {
      return set(7, currency);
    }

    /** The request. */
    public TransactionRequest build() {
      return new TransactionRequest(
          id, amount, named[0], named[1], named[2], named[3], named[4], named[5], named[6],
          named[7]);
    }
  }
}

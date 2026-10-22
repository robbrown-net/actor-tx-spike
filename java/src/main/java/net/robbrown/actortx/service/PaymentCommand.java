package net.robbrown.actortx.service;

import java.math.BigDecimal;

/**
 * A request to send a payment.
 *
 * @param amount major units ({@code 1000.00}); required and positive
 * @param merchantId whose objectives apply, or {@code null}
 * @param customerId whose objectives apply, or {@code null}
 * @param originRegion where the money comes from, or {@code null}
 * @param destinationRegion where it goes, or {@code null}
 * @param instrument {@code "spot"} or {@code "forward"}, or {@code null}
 * @param customerType {@code "business"} or {@code "retail"}, or {@code null}
 * @param sourceCurrency the currency paid in, or {@code null}
 * @param destinationCurrency the currency paid out, or {@code null}
 * @param baseline the caller's fallback route, or {@code null}
 */
public record PaymentCommand(
    BigDecimal amount,
    String merchantId,
    String customerId,
    String originRegion,
    String destinationRegion,
    String instrument,
    String customerType,
    String sourceCurrency,
    String destinationCurrency,
    String baseline) {}

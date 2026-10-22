package net.robbrown.actortx.service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * A request to quote and select.
 *
 * @param amount major units ({@code 1000.00}); defaults to zero
 * @param sourceCurrency the currency paid in, or {@code null}
 * @param destinationCurrency the currency paid out, or {@code null}
 * @param merchantId whose objectives apply, or {@code null}
 * @param customerId whose objectives apply, or {@code null}
 * @param policy routing policy data (weights, constraints) that replaces the cascade, or {@code
 *     null}
 * @param deadlineMs how long providers have, or {@code null} for the default
 * @param baseline the caller's fallback route, or {@code null}
 */
public record SelectCommand(
    BigDecimal amount,
    String sourceCurrency,
    String destinationCurrency,
    String merchantId,
    String customerId,
    Map<String, Object> policy,
    Long deadlineMs,
    String baseline) {}

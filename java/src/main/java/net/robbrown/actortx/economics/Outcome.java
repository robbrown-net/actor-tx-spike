package net.robbrown.actortx.economics;

/**
 * One finished transaction as the ledger sees it.
 *
 * @param txId the transaction
 * @param status how it finished, from the one terminal vocabulary ({@link
 *     net.robbrown.actortx.payment.Outcome})
 * @param provider the provider that carried it, or {@code null} when none did
 * @param merchant the merchant, or {@code null}
 * @param usage what the transaction consumed
 * @param discovery its price discovery, or {@code null} when there was none
 * @param margin against the merchant's standing guarantee, in minor units (negative is exposure)
 * @param providerFee the provider's fee, in minor units (already inside the quoted net)
 */
public record Outcome(
    String txId,
    net.robbrown.actortx.payment.Outcome status,
    String provider,
    String merchant,
    Measurement usage,
    Discovery discovery,
    long margin,
    long providerFee) {}

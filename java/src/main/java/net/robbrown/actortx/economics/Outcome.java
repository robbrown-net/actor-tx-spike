package net.robbrown.actortx.economics;

/**
 * One finished transaction as the ledger sees it.
 *
 * @param txId the transaction
 * @param status how it finished
 * @param provider the provider that carried it, or {@code null} when none did
 * @param merchant the merchant, or {@code null}
 * @param usage what the transaction consumed
 * @param discovery its price discovery, or {@code null} when there was none
 * @param margin against the merchant's standing guarantee, in minor units (negative is exposure)
 * @param providerFee the provider's fee, in minor units (already inside the quoted net)
 */
public record Outcome(
    String txId,
    Status status,
    String provider,
    String merchant,
    Measurement usage,
    Discovery discovery,
    long margin,
    long providerFee) {

  /** How a transaction finished. */
  public enum Status {
    /** Settled: value is earned. */
    COMPLETED,
    /** Failed: costs money, earns none. */
    FAILED,
    /** Parked for a person: costs money, earns none yet. */
    NEEDS_REVIEW
  }
}

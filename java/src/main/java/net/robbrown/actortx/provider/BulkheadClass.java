package net.robbrown.actortx.provider;

/** Which share of a provider's slots an operation may use (see {@code ProviderServer}). */
public enum BulkheadClass {
  /** New, sheddable work: quote requests. */
  QUOTE,
  /** A transaction already under way: accept, authorise, pay, settle. */
  STEP,
  /** What protects money already moved: undo operations, status lookups, probes. */
  PROTECT
}

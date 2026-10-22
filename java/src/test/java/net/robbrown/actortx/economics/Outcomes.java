package net.robbrown.actortx.economics;

/**
 * Test helper: builds an {@link Outcome}. The default is a completed transaction with the usage of
 * a million reductions, six provider calls and ten state changes, with discovery of a median of
 * 10,000, a worst of 9,000 and a best of 11,000 (minor units), no margin and a fee of 150.
 */
final class Outcomes {

  private Outcomes() {}

  /** A completed transaction on {@code provider} that netted {@code net}. */
  static Builder completed(String provider, long net) {
    return new Builder(provider, net);
  }

  /** Fluent builder for an {@link Outcome}. */
  static final class Builder {

    private final long net;
    private String txId = "tx";
    private String provider;
    private Outcome.Status status = Outcome.Status.COMPLETED;
    private boolean discovered = true;
    private long margin;
    private long fee = 150L;

    private Builder(String provider, long net) {
      this.provider = provider;
      this.net = net;
    }

    Builder txId(String value) {
      txId = value;
      return this;
    }

    Builder provider(String value) {
      provider = value;
      return this;
    }

    Builder failed() {
      status = Outcome.Status.FAILED;
      return this;
    }

    Builder needsReview() {
      status = Outcome.Status.NEEDS_REVIEW;
      return this;
    }

    Builder noDiscovery() {
      discovered = false;
      return this;
    }

    Builder margin(long value) {
      margin = value;
      return this;
    }

    Outcome build() {
      Discovery discovery = discovered ? new Discovery(3, net, 10_000L, 9_000L, 11_000L) : null;
      Measurement usage =
          Usage.none().reductions(1_000_000L).providerCalls(6L).stateChanges(10L).build();
      return new Outcome(txId, status, provider, null, usage, discovery, margin, fee);
    }
  }
}

package net.robbrown.actortx.provider;

/** How one call to a provider ended. */
public enum Outcome {
  SUCCESS,
  REJECTED,
  ERROR,
  TIMEOUT;

  /** True for the outcomes that count against a provider: an error or a timeout. */
  public boolean failure() {
    return this == ERROR || this == TIMEOUT;
  }
}

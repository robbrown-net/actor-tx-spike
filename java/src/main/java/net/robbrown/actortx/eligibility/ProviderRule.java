package net.robbrown.actortx.eligibility;

import java.util.Set;

/** Which providers may carry a corridor. */
public sealed interface ProviderRule {

  /** May this provider carry the transaction? */
  boolean permits(String providerId);

  /** Any provider may carry it. */
  record All() implements ProviderRule {
    @Override
    public boolean permits(String providerId) {
      return true;
    }
  }

  /** Only the listed providers may carry it. */
  record Allow(Set<String> providers) implements ProviderRule {
    @Override
    public boolean permits(String providerId) {
      return providers.contains(providerId);
    }
  }

  /** Every provider except the listed ones may carry it. */
  record Deny(Set<String> providers) implements ProviderRule {
    @Override
    public boolean permits(String providerId) {
      return !providers.contains(providerId);
    }
  }
}

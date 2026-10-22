package net.robbrown.actortx.eligibility;

import java.util.Set;

/** Which currencies a region allows. */
public sealed interface Currencies {

  /** May this currency code be used? */
  boolean allows(String code);

  /** Every currency is allowed. */
  record All() implements Currencies {
    @Override
    public boolean allows(String code) {
      return true;
    }
  }

  /** Only the listed currency codes are allowed. */
  record Only(Set<String> codes) implements Currencies {
    @Override
    public boolean allows(String code) {
      return codes.contains(code);
    }
  }
}

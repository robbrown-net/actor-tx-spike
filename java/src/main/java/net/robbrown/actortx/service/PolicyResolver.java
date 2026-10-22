package net.robbrown.actortx.service;

import net.robbrown.actortx.policy.PolicySet;
import net.robbrown.actortx.policy.PolicySet.Resolved;

/**
 * The routing objectives that apply to a merchant and a customer: global, then merchant, then
 * customer.
 */
public class PolicyResolver {

  private final PolicySet set;

  /** A resolver over {@code set}. */
  public PolicyResolver(PolicySet set) {
    this.set = set;
  }

  /**
   * The policy for a merchant and customer (either may be {@code null}), and which layers made it.
   */
  public Resolved resolve(String merchantId, String customerId) {
    java.util.Map<String, Object> ids = new java.util.HashMap<>();
    ids.put("merchantId", merchantId);
    ids.put("customerId", customerId);
    return set.resolve(ids);
  }
}

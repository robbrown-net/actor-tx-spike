package net.robbrown.actortx.eligibility;

import java.util.List;
import java.util.Set;

/**
 * The constraints one region imposes.
 *
 * @param instruments the instruments allowed
 * @param customerTypes the customer types allowed
 * @param currencies the currencies allowed
 * @param providers which providers may carry the corridor
 * @param logs where records go
 * @param localOnly whether records stay in these logs when this region is involved
 * @param retentionDays how long records are kept, or {@code null} for no rule
 */
public record Rule(
    Set<Instrument> instruments,
    Set<CustomerType> customerTypes,
    Currencies currencies,
    ProviderRule providers,
    List<String> logs,
    boolean localOnly,
    Integer retentionDays) {

  /** The most permissive rule: everything allowed, logged globally, no retention. */
  public static Rule defaults() {
    return new Rule(
        Set.of(Instrument.values()),
        Set.of(CustomerType.values()),
        new Currencies.All(),
        new ProviderRule.All(),
        List.of("GLOBAL"),
        false,
        null);
  }
}

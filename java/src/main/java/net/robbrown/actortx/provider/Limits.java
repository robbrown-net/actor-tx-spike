package net.robbrown.actortx.provider;

/**
 * How many calls a provider's bulkhead admits at once, by priority class. Work already in motion,
 * and the calls that protect money, are not starved by a burst of new work:
 *
 * <ul>
 *   <li>quote requests (new, sheddable) may use {@code quoteShare} of the slots (default 60%)
 *   <li>accept, authorise, pay and settle (a transaction under way) may use all of them
 *   <li>undo operations, status lookups and probes may use {@code 1 + reserveShare} of them (150%)
 * </ul>
 *
 * @param maxInFlight the slots (default 10)
 * @param quoteShare the share new work may use
 * @param reserveShare the extra share reserved for protective calls
 */
public record Limits(int maxInFlight, double quoteShare, double reserveShare) {

  /** 10 slots, 60% for quotes, 50% extra for protective calls. */
  public static Limits defaults() {
    return new Limits(10, 0.6, 0.5);
  }

  /** The same shares over a different number of slots. */
  public Limits withMaxInFlight(int slots) {
    return new Limits(slots, quoteShare, reserveShare);
  }

  /** How many calls of this class may be in flight at once. */
  public int limit(BulkheadClass bulkheadClass) {
    return switch (bulkheadClass) {
      case QUOTE -> (int) Math.ceil(maxInFlight * quoteShare);
      case STEP -> maxInFlight;
      case PROTECT -> (int) Math.ceil(maxInFlight * (1 + reserveShare));
    };
  }
}

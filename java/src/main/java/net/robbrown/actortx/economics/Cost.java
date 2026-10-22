package net.robbrown.actortx.economics;

import java.util.stream.LongStream;

/**
 * The cost of running a transaction, by resource, in pico-units.
 *
 * @param cpu CPU work
 * @param memory memory held over time
 * @param network bytes on the wire, out and in
 * @param providerCalls provider calls
 * @param storage state changes stored
 * @param total the sum of the parts
 */
public record Cost(
    long cpu, long memory, long network, long providerCalls, long storage, long total) {

  /** No cost at all. */
  public static final Cost ZERO = of(0L, 0L, 0L, 0L, 0L);

  /** A cost from its parts; the total is their sum. */
  public static Cost of(long cpu, long memory, long network, long providerCalls, long storage) {
    long total =
        LongStream.of(cpu, memory, network, providerCalls, storage).reduce(0L, Math::addExact);
    return new Cost(cpu, memory, network, providerCalls, storage, total);
  }

  /** The resource-by-resource sum of this cost and another. */
  public Cost plus(Cost other) {
    return of(
        cpu + other.cpu,
        memory + other.memory,
        network + other.network,
        providerCalls + other.providerCalls,
        storage + other.storage);
  }
}

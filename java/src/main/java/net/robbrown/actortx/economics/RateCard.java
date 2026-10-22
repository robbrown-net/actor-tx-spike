package net.robbrown.actortx.economics;

/**
 * The explicit prices of the cost model, in pico-units (10^-12 of a major currency unit).
 *
 * <p>The {@link #defaults() defaults} are illustrative placeholders to be replaced with real
 * invoice figures. Every price is a component, so a scenario ("what if egress doubles?") is one
 * changed value.
 *
 * @param cpuPicosPerMillionReductions cost of a million reductions (the runtime's unit of work)
 * @param memoryPicosPerGibSecond cost of holding one GiB of memory for one second
 * @param networkPicosPerGibOut cost of one GiB sent out
 * @param networkPicosPerGibIn cost of one GiB received
 * @param stateChangePicos storage cost of one state change
 * @param providerCallPicos cost of one call to a provider
 * @param platformPicosPerHour the always-on platform cost of one hour
 */
public record RateCard(
    long cpuPicosPerMillionReductions,
    long memoryPicosPerGibSecond,
    long networkPicosPerGibOut,
    long networkPicosPerGibIn,
    long stateChangePicos,
    long providerCallPicos,
    long platformPicosPerHour) {

  /** The placeholder rate card: illustrative cloud-like prices, not invoice figures. */
  public static RateCard defaults() {
    return new RateCard(
        111_000L, 1_389_000L, 90_000_000_000L, 0L, 250_000L, 50_000_000L, 83_200_000_000L);
  }
}

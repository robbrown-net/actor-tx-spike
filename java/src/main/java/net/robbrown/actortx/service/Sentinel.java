package net.robbrown.actortx.service;

import java.time.Duration;
import java.util.List;
import net.robbrown.actortx.provider.HealthState;
import net.robbrown.actortx.provider.ProviderServer;

/**
 * Keeps our knowledge of providers honest: probes each of them, so a provider that quietly changed
 * version (drift) is quarantined before it is trusted with money, and a quiet provider's health
 * does not go stale. Probes use the protective share of a provider's bulkhead, so they are not
 * starved by a burst of new work.
 */
public class Sentinel {

  private final ProviderCatalog catalog;
  private final Duration timeout;

  /**
   * A sentinel over {@code catalog}.
   *
   * @param timeout how long a probe may take
   */
  public Sentinel(ProviderCatalog catalog, Duration timeout) {
    this.catalog = catalog;
    this.timeout = timeout;
  }

  /** Probe every provider once; returns how many are quarantined afterwards. */
  public long probeAll() {
    List<ProviderServer> servers = catalog.servers();
    servers.forEach(server -> server.probe(timeout));
    return servers.stream().filter(s -> s.state() == HealthState.QUARANTINED).count();
  }
}

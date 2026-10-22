package net.robbrown.actortx.selection;

import java.util.Set;
import net.robbrown.actortx.provider.ProviderServer;

/**
 * A provider as the selector sees it.
 *
 * @param id the provider's name
 * @param server its facade (breaker, health, bulkhead)
 * @param currencies the currencies it carries, or {@code null} if it carries them all
 */
public record Participant(String id, ProviderServer server, Set<String> currencies) {

  /** A provider that carries every currency. */
  public static Participant of(String id, ProviderServer server) {
    return new Participant(id, server, null);
  }

  /** Does it carry both currencies of a request (a request naming none is carried by all)? */
  public boolean carries(String source, String destination) {
    return currencies == null
        || source == null
        || destination == null
        || carriesBoth(source, destination);
  }

  private boolean carriesBoth(String source, String destination) {
    return currencies.contains(source) && currencies.contains(destination);
  }
}

package net.robbrown.actortx.selection;

import java.util.Set;
import net.robbrown.actortx.provider.QuoteSource;

/**
 * A provider as the selector sees it.
 *
 * @param id the provider's name
 * @param source its facade (breaker, health, bulkhead)
 * @param currencies the currencies it carries, or {@code null} if it carries them all
 */
public record Participant(String id, QuoteSource source, Set<String> currencies) {

  /** A provider that carries every currency. */
  public static Participant of(String id, QuoteSource source) {
    return new Participant(id, source, null);
  }

  /** Does it carry both currencies of a request (a request naming none is carried by all)? */
  public boolean carries(String from, String to) {
    return currencies == null || from == null || to == null || carriesBoth(from, to);
  }

  private boolean carriesBoth(String from, String to) {
    return currencies.contains(from) && currencies.contains(to);
  }
}

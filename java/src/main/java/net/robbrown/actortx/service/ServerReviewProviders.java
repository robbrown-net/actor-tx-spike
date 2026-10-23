package net.robbrown.actortx.service;

import java.time.Duration;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.robbrown.actortx.provider.Answer;
import net.robbrown.actortx.provider.Operation;
import net.robbrown.actortx.provider.ProviderAdapter;
import net.robbrown.actortx.provider.ProviderServer;
import net.robbrown.actortx.provider.Receipt;
import net.robbrown.actortx.review.ReviewProviders;

/**
 * Lets the review workflow ask the real provider servers. Only steps that need no quote can be
 * driven again by a person: settle and the three undos; the rest are answered "not retriable".
 */
public final class ServerReviewProviders implements ReviewProviders {

  private static final Duration TIMEOUT = Duration.ofSeconds(2);

  private final Map<String, ProviderServer> servers;

  /** Over the catalog's providers. */
  public ServerReviewProviders(ProviderCatalog catalog) {
    this.servers =
        catalog.servers().stream()
            .collect(Collectors.toMap(ProviderServer::id, Function.identity()));
  }

  @Override
  public boolean available(String provider) {
    return servers.containsKey(provider);
  }

  @Override
  public Answer<Receipt> status(String provider, Operation op, String key) {
    return servers.get(provider).invoke(Operation.STATUS, a -> a.status(op, key), TIMEOUT);
  }

  @Override
  public Answer<Receipt> invoke(String provider, Operation op, String key) {
    return servers.get(provider).invoke(op, a -> drive(a, op, key), TIMEOUT);
  }

  private static final Map<Operation, BiFunction<ProviderAdapter, String, Answer<Receipt>>> DRIVES =
      Map.of(
          Operation.SETTLE, ProviderAdapter::settle,
          Operation.CANCEL_ACCEPT, ProviderAdapter::cancelAccept,
          Operation.VOID_AUTHORISATION, ProviderAdapter::voidAuthorisation,
          Operation.REFUND, ProviderAdapter::refund);

  private static Answer<Receipt> drive(ProviderAdapter adapter, Operation op, String key) {
    return DRIVES
        .getOrDefault(op, (a, k) -> new Answer.Error<>("not_retriable_without_the_quote"))
        .apply(adapter, key);
  }
}

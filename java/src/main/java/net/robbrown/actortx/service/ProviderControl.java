package net.robbrown.actortx.service;

import java.util.Map;

/** What an operator can do to the providers of the demo: break them, slow them, heal them. */
public interface ProviderControl {

  /**
   * Apply a preset to a provider.
   *
   * @param providerId which provider
   * @param action the preset ({@code "slow"}, {@code "error"}, {@code "drift"}, {@code "heal"}...)
   * @param params extra parameters (for example {@code rate} for {@code "reprice"})
   * @return what changed
   * @throws InvalidInputException for an unknown provider or preset
   */
  Map<String, Object> apply(String providerId, String action, Map<String, Object> params);
}

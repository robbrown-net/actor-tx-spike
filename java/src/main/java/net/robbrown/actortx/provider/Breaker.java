package net.robbrown.actortx.provider;

/**
 * The circuit breaker: closed lets calls through, open refuses them, half-open admits one probe.
 */
public enum Breaker {
  CLOSED,
  OPEN,
  HALF_OPEN
}

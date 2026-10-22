package net.robbrown.actortx.provider;

/**
 * What a health probe found.
 *
 * @param version the provider's reported version (a change is drift)
 * @param latencyMs the latency the provider reports for itself
 */
public record ProbeInfo(String version, long latencyMs) {}

package net.robbrown.actortx.policy;

/**
 * Relative importance of the three things a route is judged on. When built by {@link
 * Policy#fromMap} they are normalised to sum to 1.
 *
 * @param cost weight of the net value to the customer
 * @param speed weight of how fast the provider answered
 * @param reliability weight of the provider's health
 */
public record Weights(double cost, double speed, double reliability) {}

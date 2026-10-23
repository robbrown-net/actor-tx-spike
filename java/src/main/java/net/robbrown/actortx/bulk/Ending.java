package net.robbrown.actortx.bulk;

import net.robbrown.actortx.payment.Outcome;

/**
 * How an accepted payment ended.
 *
 * @param status the outcome, from the one terminal vocabulary
 * @param reason what the narrative says about a payment that did not complete; {@code null} when it
 *     completed
 */
public record Ending(Outcome status, String reason) {}

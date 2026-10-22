package net.robbrown.actortx.economics;

/**
 * The price discovery of one transaction: what the chosen quote netted, set against the other
 * quotes. All nets are in minor units.
 *
 * @param quotes how many quotes were gathered
 * @param chosenNet the net obtained from the quote that was taken
 * @param medianNet the net of the median quote (the "saved" baseline)
 * @param worstNet the net of the worst quote
 * @param bestNet the net of the best quote
 */
public record Discovery(int quotes, long chosenNet, long medianNet, long worstNet, long bestNet) {}

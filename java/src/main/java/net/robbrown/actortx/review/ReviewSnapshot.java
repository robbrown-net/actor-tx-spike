package net.robbrown.actortx.review;

import java.util.List;

/**
 * The queue's counts and entries, for the dashboard.
 *
 * @param open how many are waiting for a decision
 * @param resolved how many have been decided
 * @param entries every entry: open first, then resolved
 */
public record ReviewSnapshot(int open, int resolved, List<ReviewEntry> entries) {}

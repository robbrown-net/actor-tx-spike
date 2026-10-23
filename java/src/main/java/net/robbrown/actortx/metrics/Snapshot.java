package net.robbrown.actortx.metrics;

import java.util.List;

/**
 * What the metrics hold right now: the recent raw samples, the minute rollups and the latest
 * sample.
 *
 * @param raw the most recent samples, oldest first
 * @param minutes the closed minutes, oldest first
 * @param latest the last of {@code raw}, or {@code null} when there are none
 */
public record Snapshot(List<Sample> raw, List<MinuteRollup> minutes, Sample latest) {}

package net.robbrown.actortx.service;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Admission control: at most {@code limit} transactions in flight. Beyond that the caller is told
 * to come back, which is kinder than slowing down every transaction already admitted. The limit can
 * be changed while running.
 */
public final class Admission {

  private static final int RETRY_AFTER_MS = 100;

  private volatile int limit;
  private final AtomicInteger inFlight = new AtomicInteger();
  private final AtomicInteger peak = new AtomicInteger();
  private final AtomicLong admitted = new AtomicLong();
  private final AtomicLong rejected = new AtomicLong();

  /** Admit up to {@code limit} transactions at once. */
  public Admission(int limit) {
    this.limit = limit;
  }

  /** Take a place if there is one. */
  public boolean tryAdmit() {
    int now = inFlight.incrementAndGet();
    if (now > limit) {
      inFlight.decrementAndGet();
      rejected.incrementAndGet();
      return false;
    }
    admitted.incrementAndGet();
    peak.accumulateAndGet(now, Math::max);
    return true;
  }

  /** Give a place back. */
  public void release() {
    inFlight.decrementAndGet();
  }

  /** How many are in flight now. */
  public int inFlight() {
    return inFlight.get();
  }

  /** The most that are let in at once. */
  public int limit() {
    return limit;
  }

  /** Change the limit; places already taken are not revoked. */
  public void setLimit(int newLimit) {
    limit = newLimit;
  }

  /** The figures the dashboard shows (a limit of {@code null} means none). */
  public Map<String, Object> stats() {
    Map<String, Object> stats = new java.util.LinkedHashMap<>();
    stats.put("in_flight", inFlight.get());
    stats.put("limit", limit == Integer.MAX_VALUE ? null : limit);
    stats.put("admitted", admitted.get());
    stats.put("rejected", rejected.get());
    stats.put("peak", peak.get());
    stats.put("retry_after_ms", RETRY_AFTER_MS);
    return stats;
  }
}

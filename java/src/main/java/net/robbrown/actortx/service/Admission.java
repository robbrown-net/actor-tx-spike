package net.robbrown.actortx.service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Admission control: at most {@code limit} transactions in flight. Beyond that the caller is told
 * to come back, which is kinder than slowing down every transaction already admitted.
 */
public final class Admission {

  private final int limit;
  private final AtomicInteger inFlight = new AtomicInteger();

  /** Admit up to {@code limit} transactions at once. */
  public Admission(int limit) {
    this.limit = limit;
  }

  /** Take a place if there is one. */
  public boolean tryAdmit() {
    if (inFlight.incrementAndGet() > limit) {
      inFlight.decrementAndGet();
      return false;
    }
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
}

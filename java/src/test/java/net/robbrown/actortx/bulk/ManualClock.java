package net.robbrown.actortx.bulk;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/** A clock that only moves when a test says so. */
final class ManualClock extends Clock {

  private final AtomicLong millis;

  ManualClock(long startMs) {
    this.millis = new AtomicLong(startMs);
  }

  void advance(long ms) {
    millis.addAndGet(ms);
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }

  @Override
  public Instant instant() {
    return Instant.ofEpochMilli(millis.get());
  }
}

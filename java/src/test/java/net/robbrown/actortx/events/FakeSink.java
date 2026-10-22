package net.robbrown.actortx.events;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

/** A durable sink that records the events it is given and can be told to fail. */
final class FakeSink implements DurableSink {

  private final List<Long> seen = new CopyOnWriteArrayList<>();
  private final CountDownLatch gate;
  private final boolean crashOnWrite;
  private final boolean crashOnFlush;
  private final Optional<String> reason;

  private FakeSink(
      CountDownLatch gate, boolean crashOnWrite, boolean crashOnFlush, Optional<String> reason) {
    this.gate = gate;
    this.crashOnWrite = crashOnWrite;
    this.crashOnFlush = crashOnFlush;
    this.reason = reason;
  }

  static FakeSink ok() {
    return new FakeSink(new CountDownLatch(0), false, false, Optional.empty());
  }

  static FakeSink crashingOnWrite() {
    return new FakeSink(new CountDownLatch(0), true, false, Optional.empty());
  }

  static FakeSink crashingOnFlush() {
    return new FakeSink(new CountDownLatch(0), false, true, Optional.empty());
  }

  static FakeSink notDurable(String reason) {
    return new FakeSink(new CountDownLatch(0), false, false, Optional.of(reason));
  }

  static FakeSink gatedBy(CountDownLatch gate) {
    return new FakeSink(gate, false, false, Optional.empty());
  }

  @Override
  public void write(Event event) {
    Gates.holdUntil(gate);
    if (crashOnWrite) {
      throw new IllegalStateException("disk gone");
    }
    seen.add(event.seq());
  }

  @Override
  public Optional<String> flush() {
    if (crashOnFlush) {
      throw new IllegalStateException("no answer");
    }
    return reason;
  }

  List<Long> seen() {
    return List.copyOf(seen);
  }
}

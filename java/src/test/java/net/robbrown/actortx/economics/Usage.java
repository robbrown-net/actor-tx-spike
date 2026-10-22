package net.robbrown.actortx.economics;

/** Test helper: builds a {@link Measurement} one field at a time, every field zero by default. */
final class Usage {

  private long reductions;
  private long memoryBytes;
  private long durationMs;
  private long providerCalls;
  private long bytesOut;
  private long bytesIn;
  private long stateChanges;

  private Usage() {}

  /** Usage with nothing measured. */
  static Usage none() {
    return new Usage();
  }

  Usage reductions(long value) {
    reductions = value;
    return this;
  }

  Usage memoryBytes(long value) {
    memoryBytes = value;
    return this;
  }

  Usage durationMs(long value) {
    durationMs = value;
    return this;
  }

  Usage providerCalls(long value) {
    providerCalls = value;
    return this;
  }

  Usage bytesOut(long value) {
    bytesOut = value;
    return this;
  }

  Usage bytesIn(long value) {
    bytesIn = value;
    return this;
  }

  Usage stateChanges(long value) {
    stateChanges = value;
    return this;
  }

  Measurement build() {
    return new Measurement(
        reductions, memoryBytes, durationMs, providerCalls, bytesOut, bytesIn, stateChanges, 0L);
  }
}

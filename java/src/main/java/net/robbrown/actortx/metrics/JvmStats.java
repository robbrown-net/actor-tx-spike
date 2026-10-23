package net.robbrown.actortx.metrics;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;

/**
 * The figures of the running JVM at one moment, read through {@code java.lang.management}.
 *
 * @param heapUsed bytes of heap in use
 * @param heapMax the most heap the JVM may use, in bytes (-1 when undefined)
 * @param threads live threads
 * @param gcCount garbage collections so far, across all collectors
 */
public record JvmStats(long heapUsed, long heapMax, int threads, long gcCount) {

  /**
   * Read the JVM now.
   *
   * @return the current figures
   */
  public static JvmStats current() {
    MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    long gc =
        ManagementFactory.getGarbageCollectorMXBeans().stream()
            .mapToLong(b -> Math.max(0L, b.getCollectionCount()))
            .sum();
    return new JvmStats(
        heap.getUsed(), heap.getMax(), ManagementFactory.getThreadMXBean().getThreadCount(), gc);
  }
}

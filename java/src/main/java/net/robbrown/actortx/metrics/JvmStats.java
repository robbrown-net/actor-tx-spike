package net.robbrown.actortx.metrics;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.Optional;

/**
 * The figures of the running JVM at one moment, read through {@code java.lang.management}.
 *
 * @param heapUsed bytes of heap in use
 * @param heapMax the most heap the JVM may use, in bytes (-1 when undefined)
 * @param threads live threads
 * @param gcCount garbage collections so far, across all collectors
 * @param nonHeapUsed bytes outside the heap in use (class metadata, code cache)
 * @param gcTimeMs milliseconds spent collecting garbage so far
 * @param cpuLoad the share of one machine's CPU this process used recently, 0 to 1 (0 if unknown)
 */
public record JvmStats(
    long heapUsed,
    long heapMax,
    int threads,
    long gcCount,
    long nonHeapUsed,
    long gcTimeMs,
    double cpuLoad) {

  /** The figures that need no extra reading: heap, threads and collections. */
  public JvmStats(long heapUsed, long heapMax, int threads, long gcCount) {
    this(heapUsed, heapMax, threads, gcCount, 0, 0, 0);
  }

  /**
   * Read the JVM now.
   *
   * @return the current figures
   */
  public static JvmStats current() {
    MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    return new JvmStats(
        heap.getUsed(),
        heap.getMax(),
        ManagementFactory.getThreadMXBean().getThreadCount(),
        collections(),
        ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed(),
        collectionTimeMs(),
        processCpuLoad());
  }

  private static long collections() {
    return ManagementFactory.getGarbageCollectorMXBeans().stream()
        .mapToLong(b -> Math.max(0L, b.getCollectionCount()))
        .sum();
  }

  private static long collectionTimeMs() {
    return ManagementFactory.getGarbageCollectorMXBeans().stream()
        .mapToLong(b -> Math.max(0L, b.getCollectionTime()))
        .sum();
  }

  // The JDK's own extension of the standard bean; on other runtimes the load is reported as 0.
  private static double processCpuLoad() {
    return Optional.of(ManagementFactory.getOperatingSystemMXBean())
        .map(bean -> bean instanceof OperatingSystemMXBean os ? os.getProcessCpuLoad() : 0.0)
        .map(load -> Math.max(0.0, load))
        .orElse(0.0);
  }
}

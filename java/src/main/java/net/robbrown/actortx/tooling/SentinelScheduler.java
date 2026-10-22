package net.robbrown.actortx.tooling;

import net.robbrown.actortx.service.Sentinel;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the sentinel on a schedule ({@code actortx.sentinel.interval-ms}, default 10 s). */
@Component
public class SentinelScheduler {

  private final Sentinel sentinel;

  /** A scheduler for {@code sentinel}. */
  public SentinelScheduler(Sentinel sentinel) {
    this.sentinel = sentinel;
  }

  /** One round of probes. */
  @Scheduled(
      initialDelayString = "${actortx.sentinel.interval-ms:10000}",
      fixedDelayString = "${actortx.sentinel.interval-ms:10000}")
  public void probe() {
    sentinel.probeAll();
  }
}

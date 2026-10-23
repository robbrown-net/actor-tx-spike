package net.robbrown.actortx.service;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import net.robbrown.actortx.guarantee.Cutoff;
import net.robbrown.actortx.payment.Deadlines;
import net.robbrown.actortx.payment.TransactionRequest;

/** The regional settlement cut-offs the operator has set; a payment to a region is held to it. */
public final class CutoffBook implements Deadlines {

  private final Map<String, Cutoff> cutoffs = new ConcurrentHashMap<>();

  /** Set a region's cut-off at an absolute time. */
  public void set(String region, long atMs) {
    cutoffs.put(region, Cutoff.fixed(region, atMs));
  }

  /** Remove a region's cut-off. */
  public void clear(String region) {
    cutoffs.remove(region);
  }

  /** Milliseconds left before each region's next cut-off. */
  public Map<String, Long> remaining(long nowMs) {
    Map<String, Long> left = new TreeMap<>();
    cutoffs.forEach((region, cutoff) -> left.put(region, cutoff.remainingMs(nowMs)));
    return left;
  }

  @Override
  public Optional<Deadline> forRequest(TransactionRequest request, long nowMs) {
    return Optional.ofNullable(request.destinationRegion())
        .map(cutoffs::get)
        .map(cutoff -> new Deadline(cutoff.region(), cutoff.nextMs(nowMs)));
  }
}

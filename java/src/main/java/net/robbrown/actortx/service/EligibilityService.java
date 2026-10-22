package net.robbrown.actortx.service;

import java.util.Map;
import net.robbrown.actortx.eligibility.Decision;
import net.robbrown.actortx.eligibility.Eligibility;

/** Decides what jurisdiction allows a request, under the configured rules. */
public class EligibilityService {

  private final Eligibility eligibility;

  /** A service applying {@code eligibility}. */
  public EligibilityService(Eligibility eligibility) {
    this.eligibility = eligibility;
  }

  /** The decision for a request (keys such as {@code originRegion}, {@code instrument}). */
  public Decision decide(Map<String, Object> request) {
    return eligibility.decide(request);
  }

  /** The rules themselves, for transactions that apply them. */
  public Eligibility rules() {
    return eligibility;
  }
}

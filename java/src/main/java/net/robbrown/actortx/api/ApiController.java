package net.robbrown.actortx.api;

import java.util.List;
import java.util.Map;
import net.robbrown.actortx.eligibility.Decision;
import net.robbrown.actortx.service.EligibilityService;
import net.robbrown.actortx.service.ProviderCatalog;
import net.robbrown.actortx.service.ProviderView;
import net.robbrown.actortx.service.SelectCommand;
import net.robbrown.actortx.service.SelectionService;
import net.robbrown.actortx.service.SelectionView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The HTTP API: providers, quote-and-select, and the jurisdiction check. */
@RestController
@RequestMapping("/api")
public class ApiController {

  private final ProviderCatalog catalog;
  private final SelectionService selection;
  private final EligibilityService eligibility;

  /** Wires the services the API fronts. */
  public ApiController(
      ProviderCatalog catalog, SelectionService selection, EligibilityService eligibility) {
    this.catalog = catalog;
    this.selection = selection;
    this.eligibility = eligibility;
  }

  /** Every provider and how it looks now. */
  @GetMapping("/providers")
  public List<ProviderView> providers() {
    return catalog.views();
  }

  /** Quote and select: ask the providers, judge under the policy, return the ranking. */
  @PostMapping("/select")
  public SelectionView select(@RequestBody SelectCommand command) {
    return selection.select(command);
  }

  /** What jurisdiction allows a request. */
  @PostMapping("/eligibility")
  public Decision eligibility(@RequestBody Map<String, Object> request) {
    return eligibility.decide(request);
  }
}

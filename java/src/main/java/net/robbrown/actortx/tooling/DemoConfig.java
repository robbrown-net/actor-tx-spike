package net.robbrown.actortx.tooling;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import net.robbrown.actortx.eligibility.Eligibility;
import net.robbrown.actortx.events.EventSink;
import net.robbrown.actortx.policy.PolicySet;
import net.robbrown.actortx.selection.QuoteSelector;
import net.robbrown.actortx.service.EligibilityService;
import net.robbrown.actortx.service.PolicyResolver;
import net.robbrown.actortx.service.ProviderCatalog;
import net.robbrown.actortx.service.SelectionService;
import net.robbrown.actortx.service.Sentinel;
import net.robbrown.actortx.validation.Validated;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the demo world into the application. The demo's rules are data, in {@code resources/demo/}:
 * the policy cascade (acme pays for cost-led routing, vip-1 is speed-led) and the jurisdiction
 * rules (India and China are local-only).
 */
@Configuration
@EnableScheduling
public class DemoConfig {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** The demo's providers. */
  @Bean(destroyMethod = "close")
  public DemoWorld demoWorld(@Value("${actortx.demo.pace:1.0}") double pace) {
    return new DemoWorld(pace, EventSink.NONE);
  }

  /** Quote-and-select. */
  @Bean(destroyMethod = "close")
  public QuoteSelector quoteSelector() {
    return new QuoteSelector(EventSink.NONE);
  }

  /** The sentinel that probes the providers for drift. */
  @Bean
  public Sentinel sentinel(ProviderCatalog catalog) {
    return new Sentinel(catalog, Duration.ofSeconds(2));
  }

  /** The routing objectives: global, merchant, customer. */
  @Bean
  public PolicyResolver policyResolver() {
    return switch (PolicySet.fromMap(load("demo/policy-set.json"))) {
      case Validated.Valid<PolicySet> valid -> new PolicyResolver(valid.value());
      case Validated.Invalid<PolicySet> invalid -> throw invalid(invalid.errors());
    };
  }

  /** Quote-and-select over the demo's providers. */
  @Bean
  public SelectionService selectionService(
      ProviderCatalog catalog, QuoteSelector selector, PolicyResolver resolver) {
    return new SelectionService(catalog, selector, resolver);
  }

  /** The jurisdiction rules. */
  @Bean
  public EligibilityService eligibilityService() {
    return switch (Eligibility.fromMap(load("demo/eligibility.json"))) {
      case Validated.Valid<Eligibility> valid -> new EligibilityService(valid.value());
      case Validated.Invalid<Eligibility> invalid -> throw invalid(invalid.errors());
    };
  }

  private static IllegalStateException invalid(java.util.List<String> errors) {
    return new IllegalStateException("the demo's rules are not valid: " + errors);
  }

  private static Map<String, Object> load(String resource) {
    try (InputStream in = new ClassPathResource(resource).getInputStream()) {
      return JSON.readValue(in, new TypeReference<Map<String, Object>>() {});
    } catch (IOException unreadable) {
      throw new IllegalStateException("cannot read " + resource, unreadable);
    }
  }
}

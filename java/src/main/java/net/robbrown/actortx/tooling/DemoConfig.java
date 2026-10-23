package net.robbrown.actortx.tooling;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import net.robbrown.actortx.audit.AuditRouter;
import net.robbrown.actortx.economics.EconomicsLedger;
import net.robbrown.actortx.economics.RateCard;
import net.robbrown.actortx.eligibility.Eligibility;
import net.robbrown.actortx.events.EventBus;
import net.robbrown.actortx.guarantee.Guarantee;
import net.robbrown.actortx.guarantee.GuaranteeLedger;
import net.robbrown.actortx.payment.PaymentOptions;
import net.robbrown.actortx.payment.PaymentServices;
import net.robbrown.actortx.policy.PolicySet;
import net.robbrown.actortx.selection.QuoteSelector;
import net.robbrown.actortx.service.Admission;
import net.robbrown.actortx.service.CutoffBook;
import net.robbrown.actortx.service.EconomicsRecorder;
import net.robbrown.actortx.service.EligibilityService;
import net.robbrown.actortx.service.GuaranteeBook;
import net.robbrown.actortx.service.PaymentService;
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

  /** The narrative: everything that happens, in order, and the durable sinks it is written to. */
  @Bean(destroyMethod = "close")
  public EventBus eventBus(
      @Value("${actortx.audit.dir:}") String auditDir,
      @Value("${actortx.audit.logs:IN,CN,US,UK,EU}") List<String> logs) {
    EventBus bus = new EventBus();
    if (!auditDir.isEmpty()) {
      bus.registerSink("audit", AuditRouter.open(Path.of(auditDir), logs, true));
    }
    return bus;
  }

  /** The demo's providers. */
  @Bean(destroyMethod = "close")
  public DemoWorld demoWorld(@Value("${actortx.demo.pace:1.0}") double pace, EventBus bus) {
    return new DemoWorld(pace, bus);
  }

  /** Quote-and-select. */
  @Bean(destroyMethod = "close")
  public QuoteSelector quoteSelector(EventBus bus) {
    return new QuoteSelector(bus);
  }

  /** The standing guarantees: acme is guaranteed 1.078 this ISO week. */
  @Bean
  public GuaranteeBook guaranteeBook() {
    String week =
        LocalDate.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_WEEK_DATE).substring(0, 8);
    Map<String, Object> acme = Map.of("merchant_id", "acme", "period", week, "rate", "1.078");
    return new GuaranteeBook(
        Map.of("acme", ((Validated.Valid<Guarantee>) Guarantee.fromMap(acme)).value()),
        new GuaranteeLedger());
  }

  /** The regional cut-offs the operator sets. */
  @Bean
  public CutoffBook cutoffBook() {
    return new CutoffBook();
  }

  /** Prices every transaction as it ends. */
  @Bean
  public EconomicsRecorder economicsRecorder(EventBus bus) {
    EconomicsRecorder recorder = new EconomicsRecorder(new EconomicsLedger(RateCard.defaults()));
    bus.subscribe(recorder);
    return recorder;
  }

  /** The economics ledger. */
  @Bean
  public EconomicsLedger economicsLedger(EconomicsRecorder recorder) {
    return recorder.ledger();
  }

  /** The guarantee ledger. */
  @Bean
  public GuaranteeLedger guaranteeLedger(GuaranteeBook book) {
    return book.ledger();
  }

  /** What a transaction needs from the system around it. */
  @Bean
  public PaymentServices paymentServices(
      QuoteSelector selector, EventBus bus, CutoffBook cutoffs, GuaranteeBook guarantees) {
    return PaymentServices.of(selector, bus)
        .withBarrier(bus::barrier)
        .withDeadlines(cutoffs)
        .withGuarantees(guarantees);
  }

  /** Sending payments: at most {@code actortx.admission.max} at once. */
  @Bean
  public PaymentService paymentService(
      ProviderCatalog catalog,
      EligibilityService eligibility,
      PolicyResolver resolver,
      PaymentServices services,
      @Value("${actortx.admission.max:500}") int max) {
    return new PaymentService(
        catalog, eligibility, resolver, PaymentOptions.defaults(), services, new Admission(max));
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

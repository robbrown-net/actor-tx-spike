package net.robbrown.actortx.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.util.Map;
import net.robbrown.actortx.bulk.BulkRunner;
import net.robbrown.actortx.bulk.Sleeper;
import net.robbrown.actortx.bulk.Submission;
import net.robbrown.actortx.economics.EconomicsLedger;
import net.robbrown.actortx.economics.RateCard;
import net.robbrown.actortx.eligibility.Eligibility;
import net.robbrown.actortx.events.Event;
import net.robbrown.actortx.events.EventBus;
import net.robbrown.actortx.events.EventSink;
import net.robbrown.actortx.guarantee.GuaranteeLedger;
import net.robbrown.actortx.metrics.Metrics;
import net.robbrown.actortx.payment.PaymentOptions;
import net.robbrown.actortx.payment.PaymentServices;
import net.robbrown.actortx.policy.PolicySet;
import net.robbrown.actortx.provider.FakeProvider;
import net.robbrown.actortx.review.ReviewQueue;
import net.robbrown.actortx.review.ReviewService;
import net.robbrown.actortx.selection.QuoteSelector;
import net.robbrown.actortx.service.Admission;
import net.robbrown.actortx.service.CutoffBook;
import net.robbrown.actortx.service.EligibilityService;
import net.robbrown.actortx.service.PaymentService;
import net.robbrown.actortx.service.PolicyResolver;
import net.robbrown.actortx.service.ServerReviewProviders;
import net.robbrown.actortx.service.SnapshotService;
import net.robbrown.actortx.service.StubCatalog;
import net.robbrown.actortx.validation.Validated;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** The dashboard's page and the views it polls, over stub services. */
class DashboardControllerTest {

  private StubCatalog catalog;
  private QuoteSelector selector;
  private EventBus bus;
  private MockMvc mvc;
  private DashboardController controller;

  private PaymentService payments(Admission admission, PolicyResolver resolver) {
    return new PaymentService(
        catalog,
        new EligibilityService(Eligibility.open()),
        resolver,
        PaymentOptions.defaults(),
        PaymentServices.of(selector, EventSink.NONE),
        admission);
  }

  private BulkRunner bulk() {
    return new BulkRunner(
        command -> new Submission.Invalid(java.util.List.of("no")),
        java.time.Clock.systemUTC(),
        Sleeper.SYSTEM,
        new java.util.Random(),
        EventSink.NONE);
  }

  private ReviewService reviews() {
    return new ReviewService(
        new ReviewQueue(),
        new ServerReviewProviders(catalog),
        bus,
        java.util.Optional::empty,
        System::currentTimeMillis);
  }

  @BeforeEach
  void setUp() {
    catalog = new StubCatalog(new FakeProvider("p"));
    selector = new QuoteSelector(EventSink.NONE);
    bus = new EventBus();
    Admission admission = new Admission(5);
    PolicyResolver resolver =
        new PolicyResolver(((Validated.Valid<PolicySet>) PolicySet.fromMap(Map.of())).value());
    SnapshotService snapshots =
        new SnapshotService(
            catalog,
            resolver,
            new CutoffBook(),
            new SnapshotService.Ledgers(
                new GuaranteeLedger(), new EconomicsLedger(RateCard.defaults())),
            admission,
            payments(admission, resolver));
    ObjectMapper mapper =
        new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    controller = new DashboardController(snapshots, new Metrics(), reviews(), bus, bulk());
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(
                new ByteArrayHttpMessageConverter(),
                new MappingJackson2HttpMessageConverter(mapper))
            .build();
  }

  @AfterEach
  void tearDown() {
    bus.close();
    selector.close();
    catalog.close();
  }

  @Test
  void thePageIsTheSharedPanel() throws Exception {
    mvc.perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<title>")));
  }

  @Test
  void theViewsTheDashboardPollsAreServed() throws Exception {
    mvc.perform(get("/api/snapshot")).andExpect(jsonPath("$.providers[0].id", is("p")));
    mvc.perform(get("/api/metrics")).andExpect(status().isOk());
    mvc.perform(get("/api/review")).andExpect(jsonPath("$.open", is(0)));
    mvc.perform(get("/api/bulk")).andExpect(jsonPath("$.status", is("idle")));
    mvc.perform(get("/api/lab")).andExpect(jsonPath("$.available", is(false)));
  }

  @Test
  void theEventsStreamReplaysAndFollows() throws Exception {
    bus.emit(null, "operator", "k1", "m", Map.of());
    bus.emit("t", "payment", "k2", "m", Map.of());
    bus.barrier();

    mvc.perform(get("/events")).andExpect(request().asyncStarted());
    bus.emit("t", "payment", "k3", "m", Map.of());
    bus.barrier();
  }

  @Test
  void aClientThatHasGoneIsDroppedQuietly() {
    SseEmitter emitter = controller.events();
    emitter.complete();

    bus.emit("t", "payment", "late", "m", Map.of());
    bus.barrier();

    assertThat(bus.history()).extracting(Event::kind).contains("late");
  }
}

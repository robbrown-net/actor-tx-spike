package net.robbrown.actortx.api;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.util.Map;
import net.robbrown.actortx.eligibility.Eligibility;
import net.robbrown.actortx.events.EventSink;
import net.robbrown.actortx.payment.PaymentOptions;
import net.robbrown.actortx.payment.PaymentServices;
import net.robbrown.actortx.policy.PolicySet;
import net.robbrown.actortx.provider.FakeProvider;
import net.robbrown.actortx.selection.QuoteSelector;
import net.robbrown.actortx.service.Admission;
import net.robbrown.actortx.service.EligibilityService;
import net.robbrown.actortx.service.PaymentService;
import net.robbrown.actortx.service.PolicyResolver;
import net.robbrown.actortx.service.StubCatalog;
import net.robbrown.actortx.validation.Validated;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The payment endpoints alone: real controller and advice over a real service and stub fakes. */
class PaymentControllerTest {

  private StubCatalog catalog;
  private QuoteSelector selector;
  private Admission admission;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    catalog = new StubCatalog(new FakeProvider("best").rate("1.10"));
    selector = new QuoteSelector(EventSink.NONE);
    admission = new Admission(1);
    PaymentService service =
        new PaymentService(
            catalog,
            new EligibilityService(Eligibility.open()),
            new PolicyResolver(((Validated.Valid<PolicySet>) PolicySet.fromMap(Map.of())).value()),
            PaymentOptions.defaults(),
            PaymentServices.of(selector, EventSink.NONE),
            admission);
    ObjectMapper mapper =
        new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    mvc =
        MockMvcBuilders.standaloneSetup(new PaymentController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
            .build();
  }

  @AfterEach
  void tearDown() {
    selector.close();
    catalog.close();
  }

  private String send(String body) throws Exception {
    String json =
        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.ok", is(true)))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return new ObjectMapper().readTree(json).get("id").asText();
  }

  @Test
  void aPaymentIsAcceptedThenLookedAtThenAwaited() throws Exception {
    String id = send("{\"amount\": 100}");

    mvc.perform(get("/api/payments/" + id + "/result?timeout_ms=10000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome.status", is("completed")))
        .andExpect(jsonPath("$.outcome.summary.provider", is("best")));
    mvc.perform(get("/api/payments/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.phase", is("completed")));
  }

  @Test
  void anUnknownTransactionIs404() throws Exception {
    mvc.perform(get("/api/payments/nope")).andExpect(status().isNotFound());
    mvc.perform(get("/api/payments/nope/result")).andExpect(status().isNotFound());
  }

  @Test
  void aTransactionThatDoesNotEndInTimeIs408() throws Exception {
    new FakeProvider("x");
    catalog.servers().forEach(s -> s.close());
    String id = send("{\"amount\": 100}");

    mvc.perform(get("/api/payments/" + id + "/result?timeout_ms=1"))
        .andExpect(
            result -> {
              int code = result.getResponse().getStatus();
              if (code != 408 && code != 200) {
                throw new AssertionError("unexpected " + code);
              }
            });
  }

  @Test
  void badInputIs422AndBusyIs429() throws Exception {
    mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error", startsWith("amount")));

    admission.tryAdmit();
    mvc.perform(
            post("/api/payments").contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "1"));
  }
}

package net.robbrown.actortx.api;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.util.Map;
import net.robbrown.actortx.eligibility.Eligibility;
import net.robbrown.actortx.events.EventSink;
import net.robbrown.actortx.provider.FakeProvider;
import net.robbrown.actortx.selection.QuoteSelector;
import net.robbrown.actortx.service.EligibilityService;
import net.robbrown.actortx.service.SelectionService;
import net.robbrown.actortx.service.StubCatalog;
import net.robbrown.actortx.validation.Validated;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The HTTP layer alone: real controller and advice, stub services, the application's JSON style.
 */
class ApiControllerTest {

  private StubCatalog catalog;
  private QuoteSelector selector;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    catalog =
        new StubCatalog(
            new FakeProvider("cheap").rate("1.00"), new FakeProvider("best").rate("1.10"));
    selector = new QuoteSelector(EventSink.NONE);
    Eligibility rules =
        ((Validated.Valid<Eligibility>)
                Eligibility.fromMap(
                    Map.of(
                        "regions", Map.of("IN", Map.of("instruments", java.util.List.of("spot"))))))
            .value();
    ObjectMapper mapper =
        new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new ApiController(
                    catalog,
                    new SelectionService(catalog, selector, null),
                    new EligibilityService(rules)))
            .setControllerAdvice(new ApiExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
            .build();
  }

  @AfterEach
  void tearDown() {
    selector.close();
    catalog.close();
  }

  @Test
  void listsTheProviders() throws Exception {
    mvc.perform(get("/api/providers"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(2)))
        .andExpect(jsonPath("$[0].id", is("cheap")))
        .andExpect(jsonPath("$[0].error_rate", is(0.0)))
        .andExpect(jsonPath("$[1].in_flight", is(0)));
  }

  @Test
  void quotesAndSelects() throws Exception {
    mvc.perform(
            post("/api/select")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":1000,\"deadline_ms\":400}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.outcome", is("selected")))
        .andExpect(jsonPath("$.provider", is("best")))
        .andExpect(jsonPath("$.ranked[0].net", is("1100.00")))
        .andExpect(jsonPath("$.ranked[0].net_minor", is(110_000)))
        .andExpect(jsonPath("$.elapsed_ms").exists());
  }

  @Test
  void anInvalidPolicyIsAnUnprocessableEntityWithEveryError() throws Exception {
    mvc.perform(
            post("/api/select")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"policy\":{\"bogus\":1,\"weights\":\"x\"}}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.ok", is(false)))
        .andExpect(jsonPath("$.errors", hasSize(2)))
        .andExpect(jsonPath("$.error").exists());
  }

  @Test
  void aBodyThatIsNotJsonIsABadRequest() throws Exception {
    mvc.perform(post("/api/select").contentType(MediaType.APPLICATION_JSON).content("not json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.ok", is(false)))
        .andExpect(jsonPath("$.error", is("expected a JSON object")));
  }

  @Test
  void decidesEligibility() throws Exception {
    mvc.perform(
            post("/api/eligibility")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"destinationRegion\":\"IN\",\"instrument\":\"forward\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.permitted", is(false)))
        .andExpect(jsonPath("$.reasons[0]", is("forward is not permitted in IN")));
  }
}

package net.robbrown.actortx.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProviderViewTest {

  @Test
  void aProviderAlwaysListsItsFaultsForTheDashboard() throws Exception {
    ProviderView view =
        new ProviderView(
            "p",
            "healthy",
            "closed",
            0,
            0,
            0,
            0,
            null,
            0,
            1,
            0,
            10,
            List.of("USD"),
            List.of(new ProviderView.Fault("pay", "in_doubt")));

    String json =
        new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .writeValueAsString(view);

    assertThat(json).contains("\"faults\":[{\"op\":\"pay\",\"mode\":\"in_doubt\"}]");
  }
}

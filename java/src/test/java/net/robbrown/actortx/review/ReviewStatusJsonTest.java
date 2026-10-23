package net.robbrown.actortx.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ReviewStatusJsonTest {

  @Test
  void aStatusIsWrittenInLowerCaseForTheDashboard() throws Exception {
    ObjectMapper mapper = new ObjectMapper();

    assertThat(mapper.writeValueAsString(ReviewEntry.Status.OPEN)).isEqualTo("\"open\"");
    assertThat(ReviewEntry.Status.RESOLVED.wire()).isEqualTo("resolved");
  }
}

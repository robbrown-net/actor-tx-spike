package net.robbrown.actortx.narrative;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import net.robbrown.actortx.events.Event;
import org.junit.jupiter.api.Test;

class NarrativeTest {

  private static final long AT = 1_700_000_000_123L;

  private static Event event(String tx, String actor, String kind, String message) {
    return new Event(1, AT, tx, actor, kind, message, Map.of());
  }

  @Test
  void formatsALineWithTimeTransactionActorAndMessage() {
    String line = Narrative.format(event("tx-7", "provider:alpha", "received", "received pay"));

    assertThat(line).matches("^\\d\\d:\\d\\d:\\d\\d\\.123  tx-7 +provider:alpha +received pay$");
  }

  @Test
  void eventsWithoutATransactionShowADash() {
    String line = Narrative.format(event(null, "sentinel", "probe", "probing"));

    assertThat(line).matches("^\\d\\d:\\d\\d:\\d\\d\\.123  - +sentinel +probing$");
  }

  @Test
  void colourIsOptInAndVariesByActor() {
    assertThat(Narrative.format(event("t", "payment", "k", "m"))).doesNotContain("\u001b[");
    assertThat(Narrative.format(event("t", "payment", "k", "m"), false)).doesNotContain("\u001b[");

    for (String actor :
        new String[] {"payment", "selector", "sentinel", "provider:x", "operator"}) {
      assertThat(Narrative.format(event("t", actor, "k", "m"), true)).contains("\u001b[");
    }
    assertThat(Narrative.format(event("t", "payment", "k", "m"), true)).contains("\u001b[36m");
    assertThat(Narrative.format(event("t", "provider:x", "k", "m"), true)).contains("\u001b[32m");
    assertThat(Narrative.format(event("t", "operator", "k", "m"), true)).contains("\u001b[37m");
  }
}

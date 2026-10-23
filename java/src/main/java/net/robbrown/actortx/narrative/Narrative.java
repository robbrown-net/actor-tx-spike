package net.robbrown.actortx.narrative;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import net.robbrown.actortx.events.Event;

/**
 * Turns the event stream into a story a human can follow in a terminal:
 *
 * <pre>
 * 12:03:44.120  tx-7      payment            transaction received: amount 1000
 * 12:03:44.121  tx-7      selector           asking 5 provider(s) for quotes ...
 * 12:03:44.125  tx-7      provider:alpha     received a quote request for 1000
 * </pre>
 *
 * <p>Times are UTC. Colour is opt-in and varies by actor, so a log piped to a file stays plain.
 */
public final class Narrative {

  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneOffset.UTC);
  private static final int TX_WIDTH = 10;
  private static final int ACTOR_WIDTH = 18;
  private static final String RESET = "\u001b[0m";
  private static final int WHITE = 37;
  private static final int GREEN = 32;
  private static final Map<String, Integer> COLOURS =
      Map.of(
          "payment", 36,
          "selector", 35,
          "sentinel", 33);

  private Narrative() {}

  /**
   * One line for an event, without colour.
   *
   * @param event the event
   * @return time, transaction, actor and message, in columns
   */
  public static String format(Event event) {
    return format(event, false);
  }

  /**
   * One line for an event.
   *
   * @param event the event
   * @param colour whether to wrap the actor in ANSI colour codes
   * @return time, transaction, actor and message, in columns
   */
  public static String format(Event event, boolean colour) {
    String time = TIME.format(Instant.ofEpochMilli(event.atMs()));
    String tx = String.format("%-" + TX_WIDTH + "s", event.txId() == null ? "-" : event.txId());
    String actor = String.format("%-" + ACTOR_WIDTH + "s", event.actor());
    return time + "  " + tx + " " + colour(actor, event.actor(), colour) + " " + event.message();
  }

  private static String colour(String text, String actor, boolean colour) {
    if (!colour) {
      return text;
    }
    int code = COLOURS.getOrDefault(actor, actor.startsWith("provider:") ? GREEN : WHITE);
    return "\u001b[" + code + "m" + text + RESET;
  }
}

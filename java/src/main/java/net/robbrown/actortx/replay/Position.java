package net.robbrown.actortx.replay;

import java.util.Map;

/**
 * A moment in a day: a time, an event sequence number or an audit record number. A state is taken
 * at a position by folding every record up to and including that point.
 *
 * @param field which counter the value is in
 * @param value the largest value included
 */
public record Position(Field field, long value) {

  /** Which counter a position is measured in, and the record key that holds it. */
  public enum Field {
    /** Wall-clock milliseconds ({@code at}). */
    AT_MS("at"),
    /** The event sequence number ({@code seq}). */
    SEQ("seq"),
    /** The audit record number ({@code n}). */
    N("n");

    private final String key;

    Field(String key) {
      this.key = key;
    }

    /**
     * The record key that holds this counter.
     *
     * @return the key
     */
    public String key() {
      return key;
    }
  }

  /**
   * A position in wall-clock milliseconds.
   *
   * @param ms the time
   * @return the position
   */
  public static Position atMs(long ms) {
    return new Position(Field.AT_MS, ms);
  }

  /**
   * A position in event sequence numbers.
   *
   * @param seq the sequence number
   * @return the position
   */
  public static Position seq(long seq) {
    return new Position(Field.SEQ, seq);
  }

  /**
   * A position in audit record numbers.
   *
   * @param n the record number
   * @return the position
   */
  public static Position n(long n) {
    return new Position(Field.N, n);
  }

  /** Does a record fall at or before this position? A record without the counter does not. */
  boolean reaches(Map<String, Object> record) {
    Long counter = Fields.lng(record.get(field.key()));
    return counter != null && counter <= value;
  }
}

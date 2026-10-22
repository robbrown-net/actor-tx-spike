package net.robbrown.actortx.guarantee;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.LongStream;
import net.robbrown.actortx.validation.FieldReader;
import net.robbrown.actortx.validation.Parsed;
import net.robbrown.actortx.validation.Validated;

/**
 * A regional settlement cut-off, for example "no later than 18:00 UK local time".
 *
 * <p>Time zones are the dangerous part, so the model is explicit. A {@link Local} cut-off takes a
 * local hour and minute and a UTC offset in minutes: either a fixed offset, or a function of the
 * date so the caller can supply daylight-saving rules from a time zone database without this
 * project depending on one. A {@link Fixed} cut-off is an absolute instant.
 *
 * <p>{@link #nextMs} answers "when is the next occurrence of this cut-off, in UTC milliseconds,
 * strictly after {@code nowMs}?".
 */
public sealed interface Cutoff permits Cutoff.Fixed, Cutoff.Local {

  /**
   * The region the cut-off belongs to.
   *
   * @return the region name
   */
  String region();

  /**
   * The next occurrence of the cut-off strictly after {@code nowMs}.
   *
   * @param nowMs the current time, in UTC milliseconds
   * @return the next occurrence, in UTC milliseconds
   */
  long nextMs(long nowMs);

  /**
   * Milliseconds until the next occurrence: negative once the cut-off has passed for a fixed
   * instant, since a fixed instant does not recur.
   *
   * @param nowMs the current time, in UTC milliseconds
   * @return {@code nextMs(nowMs) - nowMs}
   */
  default long remainingMs(long nowMs) {
    return nextMs(nowMs) - nowMs;
  }

  /**
   * An absolute-instant cut-off.
   *
   * @param region the region name
   * @param atMs the instant, in UTC milliseconds
   * @return the cut-off
   */
  static Cutoff fixed(String region, long atMs) {
    return new Fixed(region, atMs);
  }

  /**
   * Validate plain data (decoded JSON) into a local cut-off. Keys are {@code region}, {@code hour}
   * (0..23), {@code minute} (0..59, default 0) and {@code utc_offset_minutes} (an integer from -840
   * to 840, or a {@code Function<LocalDate, Integer>}). Every problem is reported at once.
   *
   * @param data the decoded fields
   * @return the cut-off, or every reason it is invalid
   */
  static Validated<Cutoff> fromMap(Map<String, Object> data) {
    FieldReader reader =
        new FieldReader(Inputs.withMissingAsNull(data, "region", "hour", "utc_offset_minutes"), "");
    String region = reader.read("region", null, v -> Inputs.text(v, "required"));
    int hour = reader.read("hour", 0, v -> Inputs.inRange(v, 0, 23, "must be 0..23"));
    int minute = reader.read("minute", 0, v -> Inputs.inRange(v, 0, 59, "must be 0..59"));
    Function<LocalDate, Integer> offset =
        reader.read("utc_offset_minutes", null, v -> parseOffset(v));
    Cutoff cutoff = new Local(region, hour, minute, offset);
    return reader.finish(cutoff);
  }

  private static Parsed<Function<LocalDate, Integer>> parseOffset(Object value) {
    if (value instanceof Function<?, ?>) {
      return Parsed.ok(asOffsetFunction(value));
    }
    Parsed<Integer> minutes =
        Inputs.inRange(value, Local.MIN_OFFSET, Local.MAX_OFFSET, Local.OFFSET_MESSAGE);
    return minutes.isOk() ? Parsed.ok(constant(minutes.value())) : Parsed.fail(minutes.error());
  }

  /** The caller supplies the function; JSON cannot carry one, so only Java callers reach this. */
  @SuppressWarnings("unchecked")
  private static Function<LocalDate, Integer> asOffsetFunction(Object value) {
    return (Function<LocalDate, Integer>) value;
  }

  private static Function<LocalDate, Integer> constant(int minutes) {
    return date -> minutes;
  }

  /**
   * An absolute-instant cut-off. It occurs once, so {@link #nextMs} is always the instant itself.
   *
   * @param region the region name
   * @param atMs the instant, in UTC milliseconds
   */
  record Fixed(String region, long atMs) implements Cutoff {

    @Override
    public long nextMs(long nowMs) {
      return atMs;
    }
  }

  /**
   * A cut-off at a local time each day.
   *
   * @param region the region name
   * @param hour the local hour, 0..23
   * @param minute the local minute, 0..59
   * @param offset the UTC offset in minutes for a given date (east of UTC is positive)
   */
  record Local(String region, int hour, int minute, Function<LocalDate, Integer> offset)
      implements Cutoff {

    private static final long MINUTE_MS = 60_000L;
    private static final int MIN_OFFSET = -840;
    private static final int MAX_OFFSET = 840;
    private static final String OFFSET_MESSAGE =
        "must be an integer in -840..840 or a function of a Date";

    /**
     * Candidate dates are yesterday to the day after tomorrow, in UTC. The first candidate, in date
     * order, whose instant is strictly after {@code nowMs} wins.
     *
     * @throws IllegalStateException if no candidate lies after {@code nowMs} (only possible with an
     *     offset function returning an extreme offset)
     */
    @Override
    public long nextMs(long nowMs) {
      LocalDate today = Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate();
      return LongStream.of(-1, 0, 1, 2)
          .mapToObj(today::plusDays)
          .sorted()
          .mapToLong(date -> utcMs(date))
          .filter(ms -> ms > nowMs)
          .findFirst()
          .orElseThrow(() -> new IllegalStateException("no cut-off occurrence after " + nowMs));
    }

    private long utcMs(LocalDate date) {
      long localAsUtc =
          LocalDateTime.of(date, LocalTime.of(hour, minute)).toEpochSecond(ZoneOffset.UTC) * 1000L;
      return localAsUtc - offset.apply(date) * MINUTE_MS;
    }
  }
}

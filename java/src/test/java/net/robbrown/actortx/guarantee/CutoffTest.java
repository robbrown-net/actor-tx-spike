package net.robbrown.actortx.guarantee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.robbrown.actortx.guarantee.Cutoff.Local;
import net.robbrown.actortx.validation.Validated;
import net.robbrown.actortx.validation.Validated.Invalid;
import net.robbrown.actortx.validation.Validated.Valid;
import org.junit.jupiter.api.Test;

class CutoffTest {

  private static final long HOUR_MS = 3_600_000L;
  private static final String OFFSET_MESSAGE =
      "utc_offset_minutes: must be an integer in -840..840 or a function of a Date";

  static long ms(String iso) {
    return Instant.parse(iso).toEpochMilli();
  }

  static Map<String, Object> uk(Object hour, Object offset) {
    Map<String, Object> data = new HashMap<>();
    data.put("region", "UK");
    data.put("hour", hour);
    data.put("utc_offset_minutes", offset);
    return data;
  }

  static List<String> errorsOf(Validated<?> result) {
    return result instanceof Invalid<?> invalid ? invalid.errors() : List.of();
  }

  static Local localOf(Validated<Cutoff> result) {
    assertThat(result).isInstanceOf(Valid.class);
    return (Local) ((Valid<Cutoff>) result).value();
  }

  static Local ukAt(int hour, int minute, int offsetMinutes) {
    Map<String, Object> data = uk(hour, offsetMinutes);
    data.put("minute", minute);
    return localOf(Cutoff.fromMap(data));
  }

  static Local ukFor(Function<LocalDate, Integer> offset) {
    return localOf(Cutoff.fromMap(uk(18, offset)));
  }

  @Test
  void aLocalCutoffAcceptsAStringKeyedMapAndMinuteDefaultsToZero() {
    Local c = localOf(Cutoff.fromMap(uk(18, 60)));

    assertThat(c.region()).isEqualTo("UK");
    assertThat(c.hour()).isEqualTo(18);
    assertThat(c.minute()).isZero();
    assertThat(c.offset().apply(LocalDate.of(2026, 1, 1))).isEqualTo(60);
  }

  @Test
  void everyErrorIsCollectedAtOnce() {
    Map<String, Object> data = new HashMap<>();
    data.put("region", "");
    data.put("hour", 24);
    data.put("minute", 60);
    data.put("utc_offset_minutes", 9_999);

    assertThat(errorsOf(Cutoff.fromMap(data)))
        .containsExactly(
            "region: required", "hour: must be 0..23", "minute: must be 0..59", OFFSET_MESSAGE);
  }

  @Test
  void missingRequiredFieldsAreReported() {
    assertThat(errorsOf(Cutoff.fromMap(Map.of())))
        .containsExactly("region: required", "hour: must be 0..23", OFFSET_MESSAGE);
  }

  @Test
  void hourAndMinuteBoundariesAreInclusive() {
    assertThat(ukAt(0, 0, 0).hour()).isZero();
    assertThat(ukAt(23, 59, 0).minute()).isEqualTo(59);
    assertThat(errorsOf(Cutoff.fromMap(uk(-1, 0)))).containsExactly("hour: must be 0..23");
    assertThat(errorsOf(Cutoff.fromMap(uk(18.0, 0)))).containsExactly("hour: must be 0..23");
  }

  @Test
  void aNullMinuteIsAnErrorButAbsentMinuteIsZero() {
    Map<String, Object> data = uk(18, 0);
    data.put("minute", null);
    assertThat(errorsOf(Cutoff.fromMap(data))).containsExactly("minute: must be 0..59");
  }

  @Test
  void offsetsMustBeIntegersInRangeOrFunctions() {
    assertThat(ukFor(d -> -840).offset().apply(LocalDate.EPOCH)).isEqualTo(-840);
    assertThat(ukFor(d -> 840).offset().apply(LocalDate.EPOCH)).isEqualTo(840);
    assertThat(errorsOf(Cutoff.fromMap(uk(18, 841)))).containsExactly(OFFSET_MESSAGE);
    assertThat(errorsOf(Cutoff.fromMap(uk(18, -841)))).containsExactly(OFFSET_MESSAGE);
    assertThat(errorsOf(Cutoff.fromMap(uk(18, "60")))).containsExactly(OFFSET_MESSAGE);
    assertThat(errorsOf(Cutoff.fromMap(uk(18, 60.0)))).containsExactly(OFFSET_MESSAGE);
    assertThat(errorsOf(Cutoff.fromMap(uk(18, null)))).containsExactly(OFFSET_MESSAGE);
  }

  @Test
  void anOffsetMayBeAFunctionOfTheDate() {
    assertThat(errorsOf(Cutoff.fromMap(uk(18, (Function<LocalDate, Integer>) d -> 60)))).isEmpty();
  }

  @Test
  void laterTodayInLocalTime() {
    Local c = ukAt(18, 0, 60);
    long now = ms("2026-10-15T10:00:00Z");

    assertThat(c.nextMs(now)).isEqualTo(ms("2026-10-15T17:00:00Z"));
    assertThat(c.remainingMs(now)).isEqualTo(7 * HOUR_MS);
  }

  @Test
  void rollsToTomorrowOnceTodaysCutOffHasPassedAndExactlyAtTheInstant() {
    Local c = ukAt(18, 0, 60);

    assertThat(c.nextMs(ms("2026-10-15T17:00:01Z"))).isEqualTo(ms("2026-10-16T17:00:00Z"));
    assertThat(c.nextMs(ms("2026-10-15T17:00:00Z"))).isEqualTo(ms("2026-10-16T17:00:00Z"));
  }

  @Test
  void theLocalDateCanDifferFromTheUtcDate() {
    Local c = ukAt(17, 30, -480);

    assertThat(c.nextMs(ms("2026-10-15T20:00:00Z"))).isEqualTo(ms("2026-10-16T01:30:00Z"));
    assertThat(c.nextMs(ms("2026-10-16T01:00:00Z"))).isEqualTo(ms("2026-10-16T01:30:00Z"));
  }

  @Test
  void aDateAwareOffsetHandlesADaylightSavingChange() {
    Local c = ukFor(date -> date.isBefore(LocalDate.of(2026, 10, 25)) ? 60 : 0);

    assertThat(c.nextMs(ms("2026-10-24T10:00:00Z"))).isEqualTo(ms("2026-10-24T17:00:00Z"));
    assertThat(c.nextMs(ms("2026-10-25T10:00:00Z"))).isEqualTo(ms("2026-10-25T18:00:00Z"));
  }

  @Test
  void aFixedInstantIsReturnedAsIsAndCountsDownOnce() {
    Cutoff c = Cutoff.fixed("TEST", 123_456);

    assertThat(c.region()).isEqualTo("TEST");
    assertThat(c.nextMs(0)).isEqualTo(123_456);
    assertThat(c.remainingMs(100_000)).isEqualTo(23_456);
    assertThat(c.remainingMs(200_000)).isEqualTo(-76_544);
  }

  @Test
  void noOccurrenceInTheWindowIsAnError() {
    Local c = ukFor(date -> 100_000);

    assertThatThrownBy(() -> c.nextMs(ms("2026-10-15T10:00:00Z")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no cut-off occurrence");
  }
}

package net.robbrown.actortx.replay;

import static net.robbrown.actortx.replay.ReplayFixtures.m;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tolerant reads of records and the evidence rendering of values. */
class FieldsTest {

  @Test
  void aNonMapReadsAsAnEmptyMapAndKeysBecomeText() {
    assertThat(Fields.map("text")).isEmpty();
    assertThat(Fields.map(null)).isEmpty();
    Map<Object, Object> raw = new java.util.HashMap<>();
    raw.put(7, "seven");
    assertThat(Fields.map(raw)).containsEntry("7", "seven");
    assertThat(Fields.map(m("kept", null))).containsKey("kept");
  }

  @Test
  void aNonListReadsAsAnEmptyListAndStringsAreTexts() {
    assertThat(Fields.list(Map.of())).isEmpty();
    assertThat(Fields.list(List.of(1, "a"))).containsExactly(1, "a");
    assertThat(Fields.strings(List.of("a", 2))).containsExactly("a", "2");
    assertThat(Fields.strings(null)).isEmpty();
  }

  @Test
  void numbersAndTextAreReadOnlyWhenTheyAreThatKind() {
    assertThat(Fields.str("x")).isEqualTo("x");
    assertThat(Fields.str(3)).isNull();
    assertThat(Fields.lng(3)).isEqualTo(3L);
    assertThat(Fields.lng("3")).isNull();
    assertThat(Fields.lngOr("3", 9)).isEqualTo(9L);
    assertThat(Fields.lngOr(4.9, 9)).isEqualTo(4L);
    assertThat(Fields.dbl(2)).isEqualTo(2.0);
    assertThat(Fields.dbl("2")).isNull();
    assertThat(Fields.dblOr("2", 1.5)).isEqualTo(1.5);
    assertThat(Fields.dblOr(2.5, 1.5)).isEqualTo(2.5);
  }

  @Test
  void flagsAndMembershipAreNullSafe() {
    assertThat(Fields.flag(true)).isTrue();
    assertThat(Fields.flag(null)).isFalse();
    assertThat(Fields.oneOf(null, "a", "b")).isFalse();
    assertThat(Fields.oneOf("b", "a", "b")).isTrue();
    assertThat(Fields.is(m("kind", "x"), "x")).isTrue();
    assertThat(Fields.is(m(), "x")).isFalse();
  }

  @Test
  void dataAndPathsReadAbsentAsNull() {
    Map<String, Object> record = m("data", m("a", m("b", 5)));
    assertThat(Fields.data(record)).containsKey("a");
    assertThat(Fields.data(m())).isEmpty();
    assertThat(Fields.dig(record, "data", "a", "b")).isEqualTo(5);
    assertThat(Fields.dig(record, "data", "missing", "b")).isNull();
    assertThat(Fields.dig(record)).isSameAs(record);
  }

  @Test
  void lastAndPickKeepOnlyWhatIsThere() {
    assertThat(Fields.last(List.of())).isEmpty();
    assertThat(Fields.last(List.of(m("n", 1), m("n", 2)))).containsEntry("n", 2);
    Map<String, Object> picked = Fields.pick(m("a", 1, "b", null), List.of("a", "b", "c"));
    assertThat(picked).containsOnlyKeys("a", "b");
  }

  @Test
  void valuesAreRenderedAsElixirWouldPrintThem() {
    assertThat(Fields.inspect(null)).isEqualTo("nil");
    assertThat(Fields.inspect("text")).isEqualTo("\"text\"");
    assertThat(Fields.inspect(7)).isEqualTo("7");
    assertThat(Fields.inspect(Outcome.NEEDS_REVIEW)).isEqualTo(":needs_review");
    assertThat(Fields.inspect(List.of())).isEqualTo("[]");
    assertThat(Fields.inspect(List.of("a", "b"))).isEqualTo("[\"a\", \"b\"]");
    assertThat(Fields.inspect(Map.of())).isEqualTo("%{}");
    assertThat(Fields.inspect(m("b", 2, "a", List.of(1)))).isEqualTo("%{\"a\" => [1], \"b\" => 2}");
  }

  @Test
  void aListBuiltByHandIsMutableForMembershipTests() {
    List<String> strings = Fields.strings(List.of("x"));
    List<String> copy = new ArrayList<>(strings);
    copy.add(null);
    assertThat(copy.contains(null)).isTrue();
  }
}

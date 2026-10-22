package net.robbrown.actortx.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import net.robbrown.actortx.validation.Validated.Invalid;
import net.robbrown.actortx.validation.Validated.Valid;
import org.junit.jupiter.api.Test;

class ValidationTest {

  @Test
  void absentFieldsKeepTheirDefaultsAndPresentOnesMustParse() {
    FieldReader reader = new FieldReader(Map.of("count", 5L, "bad", "x"), "");

    long count = reader.read("count", 1L, v -> Parsers.wholeNumberAtLeast(v, 0, "no"));
    long missing = reader.read("missing", 7L, v -> Parsers.wholeNumberAtLeast(v, 0, "no"));
    long bad =
        reader.read("bad", 9L, v -> Parsers.wholeNumberAtLeast(v, 0, "must be a whole number"));

    assertThat(count).isEqualTo(5L);
    assertThat(missing).isEqualTo(7L);
    assertThat(bad).isEqualTo(9L);
    assertThat(reader.errors()).containsExactly("bad: must be a whole number");
  }

  @Test
  void unknownKeysAreReportedInOrderAndEveryErrorAtOnce() {
    FieldReader reader = new FieldReader(Map.of("zeta", 1, "alpha", 2, "n", "x"), "default.");
    reader.rejectUnknown(Set.of("n"));
    reader.read("n", 0L, v -> Parsers.wholeNumberAtLeast(v, 0, "bad"));

    assertThat(reader.errors())
        .containsExactly(
            "default.unknown key: alpha", "default.unknown key: zeta", "default.n: bad");
  }

  @Test
  void finishGivesTheValueOrEveryError() {
    FieldReader clean = new FieldReader(Map.of(), "");
    assertThat(clean.finish("ok")).isEqualTo(new Valid<>("ok"));

    FieldReader dirty = new FieldReader(Map.of("a", "x"), "");
    dirty.read("a", 0L, v -> Parsers.wholeNumberAtLeast(v, 0, "bad"));
    assertThat(dirty.finish("ignored")).isEqualTo(new Invalid<String>(java.util.List.of("a: bad")));
  }

  @Test
  void parsersAcceptWhatTheyShouldAndRefuseTheRest() {
    assertThat(Parsers.wholeNumberAtLeast(3, 0, "m").value()).isEqualTo(3L);
    assertThat(Parsers.wholeNumberAtLeast(3.0, 0, "m").isOk()).isTrue();
    assertThat(Parsers.wholeNumberAtLeast(2.5, 0, "m").isOk()).isFalse();
    assertThat(Parsers.wholeNumberAtLeast(-1, 0, "m").error()).isEqualTo("m");
    assertThat(Parsers.wholeNumberAtLeast("3", 0, "m").isOk()).isFalse();

    assertThat(Parsers.numberAtLeast(0.5, 0, "m").value()).isEqualTo(0.5);
    assertThat(Parsers.numberAtLeast(-0.1, 0, "m").isOk()).isFalse();
    assertThat(Parsers.numberAtLeast(null, 0, "m").isOk()).isFalse();

    assertThat(Parsers.bool(true, "m").value()).isTrue();
    assertThat(Parsers.bool("yes", "m").error()).isEqualTo("m");

    assertThat(Parsers.present("x")).isTrue();
    assertThat(Parsers.present("  ")).isFalse();
    assertThat(Parsers.present(5)).isFalse();
  }

  @Test
  void stringKeysNormalisesMapsAndTreatsAnythingElseAsEmpty() {
    assertThat(Parsers.stringKeys(Map.of(1, "a", "b", "c")))
        .containsEntry("1", "a")
        .containsEntry("b", "c");
    assertThat(Parsers.stringKeys("nope")).isEmpty();
    assertThat(Parsers.stringKeys(null)).isEmpty();
  }

  @Test
  void validatedOfChoosesBySide() {
    assertThat(Validated.of(1, java.util.List.of())).isInstanceOf(Valid.class);
    assertThat(Validated.of(1, java.util.List.of("e"))).isInstanceOf(Invalid.class);
    assertThat(Parsed.ok(1).isOk()).isTrue();
    assertThat(Parsed.fail("x").isOk()).isFalse();
  }
}

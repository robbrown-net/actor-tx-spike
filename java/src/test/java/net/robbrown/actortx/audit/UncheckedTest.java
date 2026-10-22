package net.robbrown.actortx.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class UncheckedTest {

  @Test
  void aValueComesBackAndAnActionRuns() {
    AtomicBoolean ran = new AtomicBoolean();
    assertThat(Unchecked.get(() -> "value")).isEqualTo("value");
    Unchecked.run(() -> ran.set(true));
    assertThat(ran).isTrue();
  }

  @Test
  void aCheckedFailureBecomesAnAuditExceptionThatKeepsTheCause() {
    IOException cause = new IOException("boom");
    assertThatThrownBy(
            () ->
                Unchecked.run(
                    () -> {
                      throw cause;
                    }))
        .isInstanceOf(AuditException.class)
        .hasCause(cause);
    assertThatThrownBy(
            () ->
                Unchecked.get(
                    () -> {
                      throw cause;
                    }))
        .isInstanceOf(AuditException.class)
        .hasMessageContaining("boom");
  }
}

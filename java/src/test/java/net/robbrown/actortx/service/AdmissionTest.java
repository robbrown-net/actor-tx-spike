package net.robbrown.actortx.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdmissionTest {

  @Test
  void placesAreGivenUntilTheLimitThenRefusedUntilOneIsReturned() {
    Admission admission = new Admission(2);

    assertThat(admission.tryAdmit()).isTrue();
    assertThat(admission.tryAdmit()).isTrue();
    assertThat(admission.tryAdmit()).isFalse();
    assertThat(admission.inFlight()).isEqualTo(2);
    assertThat(admission.limit()).isEqualTo(2);

    admission.release();

    assertThat(admission.tryAdmit()).isTrue();
  }

  @Test
  void aBusyExceptionSaysHowBusy() {
    assertThat(new BusyException(7)).hasMessage("busy: 7 transactions in flight");
  }

  @Test
  void theLimitCanBeChangedAndTheFiguresAreKept() {
    Admission admission = new Admission(1);
    admission.tryAdmit();
    admission.tryAdmit();

    admission.setLimit(Integer.MAX_VALUE);

    assertThat(admission.stats())
        .containsEntry("in_flight", 1)
        .containsEntry("limit", null)
        .containsEntry("admitted", 1L)
        .containsEntry("rejected", 1L)
        .containsEntry("peak", 1)
        .containsEntry("retry_after_ms", 100);
    admission.setLimit(4);
    assertThat(admission.stats()).containsEntry("limit", 4);
  }
}

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
}

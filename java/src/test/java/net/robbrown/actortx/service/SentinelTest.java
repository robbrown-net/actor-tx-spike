package net.robbrown.actortx.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import net.robbrown.actortx.provider.FakeProvider;
import net.robbrown.actortx.provider.HealthState;
import org.junit.jupiter.api.Test;

class SentinelTest {

  @Test
  void probesEveryProviderAndQuarantinesOneWhoseVersionChanged() {
    FakeProvider steady = new FakeProvider("steady");
    FakeProvider drifter = new FakeProvider("drifter");
    try (StubCatalog catalog = new StubCatalog(steady, drifter)) {
      Sentinel sentinel = new Sentinel(catalog, Duration.ofSeconds(1));

      assertThat(sentinel.probeAll()).isZero();
      assertThat(catalog.servers()).extracting(s -> s.state()).containsOnly(HealthState.HEALTHY);

      drifter.version("2");
      assertThat(sentinel.probeAll()).isEqualTo(1);
      assertThat(catalog.servers().get(1).state()).isEqualTo(HealthState.QUARANTINED);
      assertThat(catalog.servers().get(0).state()).isEqualTo(HealthState.HEALTHY);
    }
  }
}

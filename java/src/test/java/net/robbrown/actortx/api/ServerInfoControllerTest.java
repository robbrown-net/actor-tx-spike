package net.robbrown.actortx.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

class ServerInfoControllerTest {

  @Test
  void itNamesTheTechnologyTheArgumentsAndTheSettingsInForce() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("server.port", "4545")
            .withProperty("actortx.demo.pace", "0.3")
            .withProperty("actortx.audit.dir", "/tmp/audit");
    ServerInfoController controller =
        new ServerInfoController(
            new DefaultApplicationArguments(
                "--actortx.demo.pace=0.3", "--actortx.audit.dir=/tmp/audit"),
            env);

    Map<String, Object> info = controller.server();

    assertThat(info).containsEntry("server", "Java");
    assertThat((String) info.get("version")).contains("Spring Boot");
    assertThat(info.get("args"))
        .isEqualTo(List.of("--actortx.demo.pace=0.3", "--actortx.audit.dir=/tmp/audit"));
    assertThat(settings(info))
        .containsEntry("port", "4545")
        .containsEntry("pace", "0.3")
        .containsEntry("audit_dir", "/tmp/audit")
        .containsEntry("max_in_flight", "500");
  }

  @Test
  void settingsFallBackToTheDefaultsWhenNothingWasGiven() {
    ServerInfoController controller =
        new ServerInfoController(new DefaultApplicationArguments(), new MockEnvironment());

    assertThat(controller.server().get("args")).isEqualTo(List.of());
    assertThat(settings(controller.server()))
        .containsEntry("port", "4040")
        .containsEntry("pace", "1.0")
        .containsEntry("audit_dir", "");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> settings(Map<String, Object> info) {
    return (Map<String, Object>) info.get("settings");
  }
}

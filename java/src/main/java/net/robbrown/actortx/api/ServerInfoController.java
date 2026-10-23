package net.robbrown.actortx.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.SpringBootVersion;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** What is running: the technology and how it was started, for the dashboard's title. */
@RestController
public class ServerInfoController {

  private final ApplicationArguments arguments;
  private final Environment environment;

  /** Reads the start-up arguments and the effective settings. */
  public ServerInfoController(ApplicationArguments arguments, Environment environment) {
    this.arguments = arguments;
    this.environment = environment;
  }

  /**
   * The server's name and version, the arguments it was started with, and the settings in force
   * (which include the defaults, so a flag that was not given still shows what it came to).
   */
  @GetMapping("/api/server")
  public Map<String, Object> server() {
    Map<String, Object> info = new LinkedHashMap<>();
    info.put("server", "Java");
    info.put(
        "version",
        "%s / Spring Boot %s"
            .formatted(Runtime.version().feature(), SpringBootVersion.getVersion()));
    info.put("args", List.of(arguments.getSourceArgs()));
    info.put("settings", settings());
    return info;
  }

  private Map<String, Object> settings() {
    Map<String, Object> settings = new LinkedHashMap<>();
    settings.put("port", environment.getProperty("server.port", "4040"));
    settings.put("pace", environment.getProperty("actortx.demo.pace", "1.0"));
    settings.put("audit_dir", environment.getProperty("actortx.audit.dir", ""));
    settings.put("max_in_flight", environment.getProperty("actortx.admission.max", "500"));
    return settings;
  }
}

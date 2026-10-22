package net.robbrown.actortx;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point of the Actor TX service. */
@SpringBootApplication
public class ActorTxApplication {

  /** Spring subclasses the application class; nobody else constructs it. */
  protected ActorTxApplication() {}

  public static void main(String[] args) {
    SpringApplication.run(ActorTxApplication.class, args);
  }
}

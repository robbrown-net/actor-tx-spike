package net.robbrown.actortx.bulk;

import java.time.Clock;
import java.util.random.RandomGenerator;

/**
 * The load tool's engine: sends one plan's payments against a {@link Submitter} and measures how
 * they went. The plan sets the count, the pause between submissions (the rate), and the sender's
 * own in-flight cap (the concurrency). Runs in the calling thread and returns when every payment
 * has ended.
 */
public final class LoadRunner {

  private final Submitter submitter;
  private final Clock clock;
  private final Sleeper sleeper;
  private final RandomGenerator random;

  /**
   * A load runner.
   *
   * @param submitter where each payment is sent
   * @param clock the time source for durations and latency
   * @param sleeper pauses the sender between submissions
   * @param random the random source for unseeded plans
   */
  public LoadRunner(Submitter submitter, Clock clock, Sleeper sleeper, RandomGenerator random) {
    this.submitter = submitter;
    this.clock = clock;
    this.sleeper = sleeper;
    this.random = random;
  }

  /**
   * Send the plan's payments and measure them.
   *
   * @param label what this run is measured with, for the report (e.g. an admission limit)
   * @param plan what to send
   * @return how the run went
   */
  public LoadReport measure(String label, Plan plan) {
    Tally tally = new Tally(clock, plan);
    Sender sender =
        new Sender(
            plan, new CommandGenerator(plan, plan.generatorFor(random)), submitter, tally, sleeper);
    sender.run();
    tally.awaitEndings();
    tally.finish();
    return LoadReport.of(label, tally.snapshot());
  }
}

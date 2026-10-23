package net.robbrown.actortx.payment;

import java.util.List;

/**
 * The ordered steps of a payment. The engine runs whatever workflow it is given, so a corridor or a
 * provider can have its own (an extra screening step, a hedge, an asynchronous settlement) without
 * the engine changing.
 *
 * @param steps the steps, in order
 */
public record Workflow(List<Step> steps) {

  /** A workflow of these steps. */
  public Workflow {
    steps = List.copyOf(steps);
  }

  /** Accept, authorise, pay, settle. */
  public static Workflow standard() {
    return new Workflow(List.of(Steps.ACCEPT_QUOTE, Steps.AUTHORISE, Steps.PAY, Steps.SETTLE));
  }
}

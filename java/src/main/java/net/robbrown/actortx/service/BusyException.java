package net.robbrown.actortx.service;

/** The system is at its admission limit; try again shortly. */
public class BusyException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /** Busy, with how many are in flight. */
  public BusyException(int inFlight) {
    super("busy: " + inFlight + " transactions in flight");
  }
}

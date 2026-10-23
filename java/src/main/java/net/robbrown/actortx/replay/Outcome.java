package net.robbrown.actortx.replay;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * How a transaction ended, as far as the log says so. A transaction with no terminal record is
 * open.
 */
public enum Outcome {

  /** No terminal record yet: still in flight, or the node stopped. */
  OPEN(null),
  /** Every step succeeded and the money settled. */
  COMPLETED("tx_completed"),
  /** Ended unsuccessfully. */
  FAILED("tx_failed"),
  /** A person must decide what happened. */
  NEEDS_REVIEW("needs_review");

  private final String terminalKind;

  Outcome(String terminalKind) {
    this.terminalKind = terminalKind;
  }

  /**
   * The outcome a terminal record kind gives; open when there is no terminal record.
   *
   * @param kind the kind of the first terminal record, or null
   * @return the outcome
   */
  public static Outcome forTerminal(Object kind) {
    return Arrays.stream(values())
        .filter(outcome -> Objects.equals(outcome.terminalKind, kind))
        .findFirst()
        .orElse(OPEN);
  }

  /**
   * The record kinds that end a transaction, in order.
   *
   * @return the kinds
   */
  public static List<String> terminalKinds() {
    return Arrays.stream(values())
        .map(outcome -> outcome.terminalKind)
        .filter(Objects::nonNull)
        .toList();
  }

  /**
   * Is this record kind one that ends a transaction?
   *
   * @param kind a record kind, or null
   * @return true if it is terminal
   */
  static boolean isTerminal(Object kind) {
    return kind != null && terminalKinds().contains(kind);
  }

  /**
   * The lower-case name used in the evidence text.
   *
   * @return for example {@code "needs_review"}
   */
  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }
}

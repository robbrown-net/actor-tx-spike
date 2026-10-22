package net.robbrown.actortx.provider;

/**
 * A provider's answer, normalised into one small vocabulary so the rest of the system reasons about
 * outcomes uniformly.
 *
 * <ul>
 *   <li>{@link Ok}: answered successfully
 *   <li>{@link Rejected}: answered "no" (a business answer, not a fault)
 *   <li>{@link Error}: technical failure; nothing happened, safe to retry
 *   <li>{@link Timeout}: no answer within budget (effect unknown)
 *   <li>{@link InDoubt}: effectful operation only: the effect may have happened
 *   <li>{@link NotFound} / {@link Unknown}: {@code status} only
 * </ul>
 *
 * <p>{@link Error} with the reason {@code "overloaded"} or {@code "circuit_open"} is what the
 * system itself answers when it did not call the provider at all.
 *
 * @param <T> what an {@link Ok} carries
 */
public sealed interface Answer<T> {

  /** Answered successfully. */
  record Ok<T>(T value) implements Answer<T> {
    @Override
    public String tag() {
      return "ok";
    }

    @Override
    public Outcome outcome() {
      return Outcome.SUCCESS;
    }
  }

  /** Answered no. */
  record Rejected<T>(String reason) implements Answer<T> {
    @Override
    public String tag() {
      return "rejected";
    }

    @Override
    public Outcome outcome() {
      return Outcome.REJECTED;
    }
  }

  /** Technical failure. */
  record Error<T>(String reason) implements Answer<T> {
    @Override
    public String tag() {
      return "error";
    }

    @Override
    public Outcome outcome() {
      return Outcome.ERROR;
    }
  }

  /** No answer. */
  record Timeout<T>() implements Answer<T> {
    @Override
    public String tag() {
      return "timeout";
    }

    @Override
    public Outcome outcome() {
      return Outcome.TIMEOUT;
    }
  }

  /** The effect may have happened; the answer was lost. */
  record InDoubt<T>() implements Answer<T> {
    @Override
    public String tag() {
      return "in_doubt";
    }

    @Override
    public Outcome outcome() {
      return Outcome.TIMEOUT;
    }
  }

  /** Nothing is held for that key (yet). */
  record NotFound<T>() implements Answer<T> {
    @Override
    public String tag() {
      return "not_found";
    }

    @Override
    public Outcome outcome() {
      return Outcome.SUCCESS;
    }
  }

  /** The provider cannot say. */
  record Unknown<T>() implements Answer<T> {
    @Override
    public String tag() {
      return "unknown";
    }

    @Override
    public Outcome outcome() {
      return Outcome.ERROR;
    }
  }

  /** The short word for the narrative and the audit log: {@code "ok"}, {@code "timeout"}... */
  String tag();

  /**
   * How this answer counts in the health model: success, rejected (a business answer, not a fault),
   * error or timeout. A not-found status lookup is a success; so is an answered "no".
   */
  Outcome outcome();

  /** Was it a success? */
  default boolean isOk() {
    return this instanceof Ok;
  }
}

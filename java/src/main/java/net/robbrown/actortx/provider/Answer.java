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
  }

  /** Answered no. */
  record Rejected<T>(String reason) implements Answer<T> {
    @Override
    public String tag() {
      return "rejected";
    }
  }

  /** Technical failure. */
  record Error<T>(String reason) implements Answer<T> {
    @Override
    public String tag() {
      return "error";
    }
  }

  /** No answer. */
  record Timeout<T>() implements Answer<T> {
    @Override
    public String tag() {
      return "timeout";
    }
  }

  /** The effect may have happened; the answer was lost. */
  record InDoubt<T>() implements Answer<T> {
    @Override
    public String tag() {
      return "in_doubt";
    }
  }

  /** Nothing is held for that key (yet). */
  record NotFound<T>() implements Answer<T> {
    @Override
    public String tag() {
      return "not_found";
    }
  }

  /** The provider cannot say. */
  record Unknown<T>() implements Answer<T> {
    @Override
    public String tag() {
      return "unknown";
    }
  }

  /** The short word for the narrative and the audit log: {@code "ok"}, {@code "timeout"}... */
  String tag();

  /** Was it a success? */
  default boolean isOk() {
    return this instanceof Ok;
  }
}

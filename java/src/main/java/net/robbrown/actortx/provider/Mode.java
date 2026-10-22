package net.robbrown.actortx.provider;

/**
 * What a scripted fake provider does with one call.
 *
 * <ul>
 *   <li>{@code OK}
 *   <li>{@code TIMEOUT}: no answer; the effect did NOT happen
 *   <li>{@code error(reason)}: technical failure; the effect did NOT happen
 *   <li>{@code REJECT}: a business "no"
 *   <li>{@code IN_DOUBT}: effectful operations: the effect HAPPENS but the answer is lost
 *   <li>{@code UNKNOWN}: {@code status} only: the provider cannot say
 * </ul>
 *
 * @param kind which of them
 * @param reason the reason, for {@code error}
 */
public record Mode(Kind kind, String reason) {

  /** The kinds of behaviour. */
  public enum Kind {
    OK,
    TIMEOUT,
    ERROR,
    REJECT,
    IN_DOUBT,
    UNKNOWN
  }

  /** Behave. */
  public static final Mode OK = new Mode(Kind.OK, null);

  /** Say nothing. */
  public static final Mode TIMEOUT = new Mode(Kind.TIMEOUT, null);

  /** Say no. */
  public static final Mode REJECT = new Mode(Kind.REJECT, null);

  /** Do it and lose the answer. */
  public static final Mode IN_DOUBT = new Mode(Kind.IN_DOUBT, null);

  /** Cannot say. */
  public static final Mode UNKNOWN = new Mode(Kind.UNKNOWN, null);

  /** Fail technically with a reason. */
  public static Mode error(String reason) {
    return new Mode(Kind.ERROR, reason);
  }

  /** Does this mode fail without doing anything (timeout or error)? */
  public boolean failing() {
    return kind == Kind.TIMEOUT || kind == Kind.ERROR;
  }
}

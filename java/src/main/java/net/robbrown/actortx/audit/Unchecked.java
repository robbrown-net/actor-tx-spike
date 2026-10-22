package net.robbrown.actortx.audit;

import java.util.concurrent.Callable;

/**
 * Turns the checked failures of file, JSON and digest calls into {@link AuditException}. Every such
 * call in the audit trail goes through here, so a failure always fails closed the same way.
 */
final class Unchecked {

  private Unchecked() {}

  /** An action that may fail with any checked exception. */
  @FunctionalInterface
  interface Action {
    /**
     * Do the action.
     *
     * @throws Exception whatever went wrong
     */
    void run() throws Exception;
  }

  /**
   * Run an action that returns nothing.
   *
   * @param action what to do
   * @throws AuditException if the action fails
   */
  static void run(Action action) {
    get(
        () -> {
          action.run();
          return null;
        });
  }

  /**
   * Compute a value that may fail.
   *
   * @param action what to compute
   * @return the value
   * @param <T> the type of the value
   * @throws AuditException if the computation fails
   */
  static <T> T get(Callable<T> action) {
    try {
      return action.call();
    } catch (Exception e) {
      throw new AuditException(e.toString(), e);
    }
  }
}

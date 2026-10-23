package net.robbrown.actortx.replay;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Compare two days, for example this system against another running in parallel, matched by
 * transaction id. Per transaction it compares the outcome, the provider the payment went through,
 * the net value achieved and the steps completed. Timing is deliberately not compared: two systems
 * never agree to the millisecond.
 */
public final class Compare {

  private Compare() {}

  /** What is compared per transaction. */
  public enum Field {
    /** How the transaction ended. */
    OUTCOME(summary -> summary.outcome()),
    /** The provider it ended with. */
    PROVIDER(summary -> summary.provider()),
    /** The net value achieved. */
    NET(summary -> summary.net()),
    /** The steps completed at that provider. */
    STEPS(summary -> summary.steps());

    private final Function<TxSummary, Object> reader;

    Field(Function<TxSummary, Object> reader) {
      this.reader = reader;
    }

    /**
     * This field of a summary.
     *
     * @param summary the summary
     * @return the value
     */
    Object of(TxSummary summary) {
      return reader.apply(summary);
    }
  }

  /**
   * A transaction whose two days disagree on a field.
   *
   * @param tx the transaction
   * @param field the field
   * @param a the value in the first day
   * @param b the value in the second day
   */
  public record Divergence(String tx, Field field, Object a, Object b) {}

  /**
   * The comparison.
   *
   * @param common how many transactions are in both days
   * @param identical how many of those agree on every field
   * @param onlyA transactions only the first day has
   * @param onlyB transactions only the second day has
   * @param divergences every disagreement
   */
  public record Diff(
      int common,
      int identical,
      List<String> onlyA,
      List<String> onlyB,
      List<Divergence> divergences) {}

  /**
   * Compare two days.
   *
   * @param a the first day
   * @param b the second day
   * @return the comparison
   */
  public static Diff diff(Day a, Day b) {
    List<String> onlyA = missingFrom(a, b);
    List<String> common = a.txOrder().stream().filter(id -> !onlyA.contains(id)).toList();
    List<Divergence> divergences = divergences(a, b, common);
    long diverging = divergences.stream().map(Divergence::tx).distinct().count();
    return new Diff(
        common.size(), common.size() - (int) diverging, onlyA, missingFrom(b, a), divergences);
  }

  /** Transactions one day has that the other does not, in arrival order. */
  private static List<String> missingFrom(Day day, Day other) {
    return day.txOrder().stream().filter(id -> !other.txOrder().contains(id)).toList();
  }

  private static List<Divergence> divergences(Day a, Day b, List<String> ids) {
    return ids.stream()
        .flatMap(
            id ->
                Arrays.stream(Field.values())
                    .map(field -> divergence(a, b, id, field))
                    .flatMap(Optional::stream))
        .toList();
  }

  private static Optional<Divergence> divergence(Day a, Day b, String id, Field field) {
    Object x = field.of(Tx.summarize(a.byTx().get(id)));
    Object y = field.of(Tx.summarize(b.byTx().get(id)));
    if (Objects.equals(x, y)) {
      return Optional.empty();
    }
    return Optional.of(new Divergence(id, field, x, y));
  }
}

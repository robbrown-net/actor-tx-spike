package net.robbrown.actortx.guarantee;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

/**
 * Accounts for standing guarantees: per merchant and period, how much margin we kept and how much
 * exposure we carried. In memory only (a durable store is a later phase). Recording is idempotent
 * per transaction id: the first entry for a transaction is the one that counts.
 *
 * <p>Every method is synchronized, so the ledger can be shared between request threads.
 */
public final class GuaranteeLedger {

  private static final Comparator<Row> BY_MERCHANT_AND_PERIOD =
      Comparator.comparing(Row::merchantId).thenComparing(Row::period);

  private final Map<String, Entry> entries = new HashMap<>();

  /**
   * One settled transaction recorded against a guarantee.
   *
   * @param txId the transaction id; a second entry with the same id is ignored
   * @param merchantId the merchant
   * @param period the guarantee period
   * @param guaranteedNet the net amount the guarantee promised, in minor units
   * @param achievedNet the net amount achieved, in minor units
   * @param margin achieved minus guaranteed: positive is margin, negative is exposure
   */
  public record Entry(
      String txId,
      String merchantId,
      String period,
      long guaranteedNet,
      long achievedNet,
      long margin) {}

  /**
   * The totals for one merchant and period. A group with no entries has every total at zero.
   *
   * @param merchantId the merchant
   * @param period the period
   * @param count the number of transactions
   * @param guaranteedTotal the sum of the guaranteed net amounts, in minor units
   * @param achievedTotal the sum of the achieved net amounts, in minor units
   * @param marginTotal the sum of the margins (negative when we are short)
   * @param exposureTotal the sum of the negative margins only (zero or less)
   * @param worst the smallest margin, zero when there are no entries
   * @param best the largest margin, zero when there are no entries
   */
  public record Row(
      String merchantId,
      String period,
      long count,
      long guaranteedTotal,
      long achievedTotal,
      long marginTotal,
      long exposureTotal,
      long worst,
      long best) {}

  /** A merchant and period, the key entries are grouped by. */
  private record Group(String merchantId, String period) {}

  /**
   * Record one settled transaction. Recording the same transaction id again changes nothing.
   *
   * @param entry the transaction
   */
  public synchronized void record(Entry entry) {
    entries.putIfAbsent(entry.txId(), entry);
  }

  /**
   * The totals for one merchant and period.
   *
   * @param merchantId the merchant
   * @param period the period
   * @return the totals; zeros when nothing has been recorded for the pair
   */
  public synchronized Row summary(String merchantId, String period) {
    List<Entry> group =
        entries.values().stream().filter(e -> matches(e, merchantId, period)).toList();
    return totals(new Group(merchantId, period), group);
  }

  /**
   * The totals for every merchant and period with at least one entry, sorted by merchant and then
   * period.
   *
   * @return one row per merchant and period
   */
  public synchronized List<Row> all() {
    return entries.values().stream()
        .collect(Collectors.groupingBy(e -> groupOf(e)))
        .entrySet()
        .stream()
        .map(e -> totals(e.getKey(), e.getValue()))
        .sorted(BY_MERCHANT_AND_PERIOD)
        .toList();
  }

  /** Forget every entry. */
  public synchronized void reset() {
    entries.clear();
  }

  private static boolean matches(Entry entry, String merchantId, String period) {
    return Objects.equals(entry.merchantId(), merchantId) && Objects.equals(entry.period(), period);
  }

  private static Group groupOf(Entry entry) {
    return new Group(entry.merchantId(), entry.period());
  }

  private static Row totals(Group key, List<Entry> group) {
    LongSummaryStatistics margins = group.stream().mapToLong(Entry::margin).summaryStatistics();
    return new Row(
        key.merchantId(),
        key.period(),
        group.size(),
        sum(group, Entry::guaranteedNet),
        sum(group, Entry::achievedNet),
        margins.getSum(),
        group.stream().mapToLong(Entry::margin).filter(m -> m < 0).sum(),
        group.isEmpty() ? 0L : margins.getMin(),
        group.isEmpty() ? 0L : margins.getMax());
  }

  private static long sum(List<Entry> group, ToLongFunction<Entry> field) {
    return group.stream().mapToLong(field).sum();
  }
}

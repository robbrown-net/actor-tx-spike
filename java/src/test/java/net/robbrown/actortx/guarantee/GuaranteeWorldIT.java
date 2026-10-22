package net.robbrown.actortx.guarantee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.robbrown.actortx.guarantee.GuaranteeLedger.Entry;
import net.robbrown.actortx.guarantee.GuaranteeLedger.Row;
import net.robbrown.actortx.validation.Validated;
import net.robbrown.actortx.validation.Validated.Invalid;
import net.robbrown.actortx.validation.Validated.Valid;
import org.junit.jupiter.api.Test;

/** The guarantee flow end to end: guarantees and cut-offs validated, evaluated and accounted. */
class GuaranteeWorldIT {

  private static final Instant TUESDAY_MORNING = Instant.parse("2026-10-20T10:00:00Z");

  /** A settled sale: its amount and what the route achieved net, in minor units. */
  record Sale(String txId, long amount, long achieved) {}

  @Test
  void aWeeklyGuaranteeIsAccountedAcrossSales() {
    Guarantee guarantee = valid(Guarantee.fromMap(acme("1.0820")));
    GuaranteeLedger ledger = new GuaranteeLedger();
    List.of(
            new Sale("s1", 10_000, 10_900),
            new Sale("s2", 5_000, 5_300),
            new Sale("s3", 2_000, 2_170))
        .forEach(sale -> book(ledger, guarantee, sale));

    Row acme = ledger.summary("acme", "2026-W42");
    assertThat(acme.count()).isEqualTo(3);
    assertThat(acme.guaranteedTotal()).isEqualTo(18_394);
    assertThat(acme.achievedTotal()).isEqualTo(18_370);
    assertThat(acme.marginTotal()).isEqualTo(-24);
    assertThat(acme.exposureTotal()).isEqualTo(-110);
    assertThat(acme.best()).isEqualTo(80);
    assertThat(acme.worst()).isEqualTo(-110);
  }

  @Test
  void theLedgerReportsEveryGroupInOrderAndCanBeReset() {
    Guarantee acme = valid(Guarantee.fromMap(acme("1.0")));
    Guarantee globex =
        valid(Guarantee.fromMap(Map.of("merchant_id", "globex", "period", "W1", "rate", 2)));
    GuaranteeLedger ledger = new GuaranteeLedger();
    book(ledger, acme, new Sale("a", 100, 101));
    book(ledger, globex, new Sale("b", 100, 199));

    assertThat(ledger.all().stream().map(Row::merchantId).toList())
        .containsExactly("acme", "globex");
    assertThat(ledger.all().get(1).marginTotal()).isEqualTo(-1);

    ledger.reset();
    assertThat(ledger.all()).isEmpty();
  }

  @Test
  void aBadGuaranteeIsRefusedWithItsReason() {
    Validated<Guarantee> result = Guarantee.fromMap(acme("cheap"));
    assertThat(result).isInstanceOf(Invalid.class);
    assertThat(((Invalid<Guarantee>) result).errors())
        .containsExactly("rate: must be a positive number");
  }

  @Test
  void aLocalCutOffFollowsADaylightSavingRule() {
    Cutoff uk =
        valid(Cutoff.fromMap(ukCutoff(date -> date.isBefore(LocalDate.of(2026, 10, 25)) ? 60 : 0)));

    long beforeChange = uk.nextMs(TUESDAY_MORNING.toEpochMilli());
    assertThat(beforeChange).isEqualTo(Instant.parse("2026-10-20T17:00:00Z").toEpochMilli());
    assertThat(uk.remainingMs(TUESDAY_MORNING.toEpochMilli())).isEqualTo(7L * 3_600_000L);

    long afterChange = uk.nextMs(Instant.parse("2026-10-25T10:00:00Z").toEpochMilli());
    assertThat(afterChange).isEqualTo(Instant.parse("2026-10-25T18:00:00Z").toEpochMilli());
  }

  @Test
  void aFixedCutOffCountsDownOnce() {
    Cutoff fixed = Cutoff.fixed("NYSE", Instant.parse("2026-10-20T20:00:00Z").toEpochMilli());
    assertThat(fixed.region()).isEqualTo("NYSE");
    assertThat(fixed.remainingMs(TUESDAY_MORNING.toEpochMilli())).isEqualTo(10L * 3_600_000L);
    assertThat(fixed.remainingMs(Instant.parse("2026-10-20T21:00:00Z").toEpochMilli()))
        .isNegative();
  }

  @Test
  void aCutOffWithNoSensibleOccurrenceIsAnError() {
    Cutoff broken = valid(Cutoff.fromMap(ukCutoff(date -> 100_000)));
    assertThatThrownBy(() -> broken.nextMs(TUESDAY_MORNING.toEpochMilli()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aBadCutOffReportsEveryProblem() {
    Validated<Cutoff> result = Cutoff.fromMap(Map.of("region", "", "hour", 30));
    assertThat(((Invalid<Cutoff>) result).errors())
        .containsExactly(
            "region: required",
            "hour: must be 0..23",
            "utc_offset_minutes: must be an integer in -840..840 or a function of a Date");
  }

  private static Map<String, Object> acme(Object rate) {
    return Map.of("merchant_id", "acme", "period", "2026-W42", "rate", rate);
  }

  private static Map<String, Object> ukCutoff(Function<LocalDate, Integer> offset) {
    return Map.of("region", "UK", "hour", 18, "minute", 0, "utc_offset_minutes", offset);
  }

  private static void book(GuaranteeLedger ledger, Guarantee guarantee, Sale sale) {
    Guarantee.Evaluation evaluation = guarantee.evaluate(sale.amount(), sale.achieved());
    ledger.record(
        new Entry(
            sale.txId(),
            guarantee.merchantId(),
            guarantee.period(),
            evaluation.guaranteedNet(),
            evaluation.achievedNet(),
            evaluation.margin()));
  }

  private static <T> T valid(Validated<T> result) {
    assertThat(result).isInstanceOf(Valid.class);
    return ((Valid<T>) result).value();
  }
}

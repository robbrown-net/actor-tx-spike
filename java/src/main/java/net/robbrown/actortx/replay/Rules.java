package net.robbrown.actortx.replay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.robbrown.actortx.money.Money;

/**
 * Did the day follow the rules? Every check works from the recorded log alone.
 *
 * <p>Money safety: no transaction paid twice; a completed transaction ran the four steps in order
 * at one provider; whatever a provider was left holding was undone; no failover after a money step
 * was in doubt; calls used the keyed idempotency scheme; no provider outside the recorded
 * jurisdiction decision was used. Decisions are recomputed with the code the live system used: the
 * ranking, the quote's validity at accept, the deadline, the margin and the price discovery. Record
 * hygiene: time never runs backwards.
 *
 * <p>Each violation names the transaction and the audit record it is evidenced by, so the proof can
 * be pulled straight from the log.
 */
public final class Rules {

  /** Default slack on the deadline: event stamps can trail the decision by a few milliseconds. */
  public static final long DEFAULT_TOLERANCE_MS = 50;

  private static final Map<String, String> SHORT =
      Map.of("accept_quote", "accept", "authorise", "authorise", "pay", "pay", "settle", "settle");

  private static final List<Check> CHECKS =
      List.of(
          ctx -> singlePay(ctx),
          ctx -> completedIsWhole(ctx),
          ctx -> failedClean(ctx),
          ctx -> noFailoverAfterDoubt(ctx),
          ctx -> idempotencyKeys(ctx),
          ctx -> eligibilityRespected(ctx),
          ctx -> reviewRecorded(ctx),
          ctx -> selectionReproducible(ctx),
          ctx -> quoteValidAtAccept(ctx),
          ctx -> deadlineRespected(ctx),
          ctx -> marginArithmetic(ctx),
          ctx -> economicsConsistent(ctx));

  private Rules() {}

  /** One broken rule, with its evidence. */
  public record Violation(Rule rule, String tx, Long n, String detail) {}

  /**
   * The result of checking a day.
   *
   * @param records how many records the day has
   * @param transactions how many transactions it has
   * @param outcomes how many transactions ended in each outcome
   * @param violations every rule broken
   */
  public record Verdict(
      int records, int transactions, Map<Outcome, Long> outcomes, List<Violation> violations) {

    /**
     * Did every rule hold?
     *
     * @return true if there are no violations
     */
    public boolean ok() {
      return violations.isEmpty();
    }
  }

  /** What a check sees about one transaction. */
  private record Ctx(String id, List<Map<String, Object>> rs, TxSummary summary, long tolerance) {}

  /** One rule over one transaction. */
  @FunctionalInterface
  private interface Check {
    List<Violation> apply(Ctx ctx);
  }

  /** A recorded ranking entry: who, and the net they were worth. */
  private record Entry(String provider, Long net) {}

  /**
   * Check a day with the default tolerance.
   *
   * @param day the day
   * @return the verdict
   */
  public static Verdict verify(Day day) {
    return verify(day, DEFAULT_TOLERANCE_MS);
  }

  /**
   * Check a day.
   *
   * @param day the day
   * @param toleranceMs slack on the deadline, in milliseconds
   * @return the verdict
   */
  public static Verdict verify(Day day, long toleranceMs) {
    List<Violation> violations = new ArrayList<>(timeOrdered(day.records()));
    for (String id : day.txOrder()) {
      violations.addAll(checkTx(id, day.byTx().get(id), toleranceMs));
    }
    return new Verdict(
        day.records().size(), day.txOrder().size(), Tx.frequencies(day.transactions()), violations);
  }

  private static List<Violation> checkTx(String id, List<Map<String, Object>> rs, long tolerance) {
    Ctx ctx = new Ctx(id, rs, Tx.summarize(rs), tolerance);
    return CHECKS.stream().flatMap(check -> check.apply(ctx).stream()).toList();
  }

  private static List<Violation> singlePay(Ctx c) {
    List<Map<String, Object>> pays =
        c.rs().stream()
            .filter(
                r ->
                    Fields.oneOf(r.get("kind"), "step_succeeded", "reconciled")
                        && "pay".equals(Fields.data(r).get("op")))
            .toList();
    if (pays.size() <= 1) {
      return List.of();
    }
    return List.of(
        violation(
            Rule.SINGLE_PAY, c.id(), Fields.last(pays), "pay succeeded " + pays.size() + " times"));
  }

  private static List<Violation> completedIsWhole(Ctx c) {
    TxSummary s = c.summary();
    if (s.outcome() != Outcome.COMPLETED) {
      return List.of();
    }
    List<String> got = Tx.succeededOps(c.rs(), s.provider());
    if (got.equals(Tx.EXPECTED_STEPS)) {
      return List.of();
    }
    String detail =
        "ran %s at %s, expected %s (completed)"
            .formatted(Fields.inspect(got), s.provider(), Fields.inspect(Tx.EXPECTED_STEPS));
    return List.of(
        violation(Rule.COMPLETED_IS_WHOLE, c.id(), Tx.first(c.rs(), "tx_completed"), detail));
  }

  private static List<Violation> failedClean(Ctx c) {
    List<String> unique =
        Tx.all(c.rs(), "provider_chosen").stream()
            .map(r -> Fields.str(Fields.data(r).get("provider")))
            .distinct()
            .toList();
    List<String> abandoned =
        c.summary().outcome() == Outcome.FAILED
            ? unique
            : unique.subList(0, Math.max(0, unique.size() - 1));
    return abandoned.stream().map(p -> leftHolding(c, p)).flatMap(Optional::stream).toList();
  }

  private static Optional<Violation> leftHolding(Ctx c, String provider) {
    List<String> missing = undoneMissing(c.rs(), provider);
    if (missing.isEmpty()) {
      return Optional.empty();
    }
    Map<String, Object> last =
        Fields.last(
            c.rs().stream()
                .filter(r -> Objects.equals(Fields.data(r).get("provider"), provider))
                .toList());
    return Optional.of(
        violation(
            Rule.FAILED_CLEAN,
            c.id(),
            last,
            provider + " left holding " + Fields.inspect(missing) + " without undo"));
  }

  /** The undo operations still owed by a provider: what it did, less what was undone. */
  private static List<String> undoneMissing(List<Map<String, Object>> rs, String provider) {
    List<String> owed =
        new ArrayList<>(
            Tx.succeededOps(rs, provider).stream()
                .map(Tx::undoFor)
                .flatMap(Optional::stream)
                .toList());
    Tx.all(rs, "undone").stream()
        .filter(r -> Objects.equals(Fields.data(r).get("provider"), provider))
        .map(r -> Fields.str(Fields.data(r).get("op")))
        .forEach(op -> owed.remove(op));
    return owed;
  }

  private static List<Violation> noFailoverAfterDoubt(Ctx c) {
    List<Map<String, Object>> rs = c.rs();
    OptionalInt doubt =
        IntStream.range(0, rs.size()).filter(i -> isMoneyDoubt(rs.get(i))).findFirst();
    if (doubt.isEmpty()) {
      return List.of();
    }
    return rs.subList(doubt.getAsInt() + 1, rs.size()).stream()
        .filter(r -> Fields.oneOf(r.get("kind"), "provider_chosen", "failover"))
        .map(
            r ->
                violation(
                    Rule.NO_FAILOVER_AFTER_DOUBT,
                    c.id(),
                    r,
                    r.get("kind") + " after pay or settle was in doubt"))
        .toList();
  }

  private static boolean isMoneyDoubt(Map<String, Object> record) {
    return Fields.is(record, "in_doubt")
        && Fields.oneOf(Fields.data(record).get("op"), "pay", "settle");
  }

  private static List<Violation> idempotencyKeys(Ctx c) {
    List<Map<String, Object>> calls = Tx.all(c.rs(), "step_started");
    List<Violation> wrong =
        calls.stream()
            .filter(r -> !expectedKey(c.id(), r).equals(Fields.str(Fields.data(r).get("key"))))
            .map(
                r ->
                    violation(
                        Rule.IDEMPOTENCY_KEYS,
                        c.id(),
                        r,
                        "key %s is not %s"
                            .formatted(
                                Fields.inspect(Fields.data(r).get("key")), expectedKey(c.id(), r))))
            .toList();
    return Stream.concat(wrong.stream(), varyingKeys(c, calls).stream()).toList();
  }

  private static String expectedKey(String id, Map<String, Object> record) {
    Map<String, Object> d = Fields.data(record);
    String op = Fields.str(d.get("op"));
    String step = Optional.ofNullable(op).map(SHORT::get).orElse("");
    return id + ":" + Objects.toString(d.get("provider"), "") + ":" + step;
  }

  private static List<Violation> varyingKeys(Ctx c, List<Map<String, Object>> calls) {
    Map<List<Object>, List<Map<String, Object>>> groups = new LinkedHashMap<>();
    for (Map<String, Object> call : calls) {
      Map<String, Object> d = Fields.data(call);
      groups
          .computeIfAbsent(Arrays.asList(d.get("provider"), d.get("op")), key -> new ArrayList<>())
          .add(call);
    }
    return groups.values().stream()
        .filter(group -> keysOf(group).size() > 1)
        .map(group -> varyingViolation(c, group))
        .toList();
  }

  private static Violation varyingViolation(Ctx c, List<Map<String, Object>> group) {
    Map<String, Object> first = group.get(0);
    Map<String, Object> d = Fields.data(first);
    return violation(
        Rule.IDEMPOTENCY_KEYS,
        c.id(),
        first,
        "%s %s used several keys: %s"
            .formatted(d.get("provider"), d.get("op"), Fields.inspect(keysOf(group))));
  }

  private static List<Object> keysOf(List<Map<String, Object>> group) {
    return group.stream().map(r -> Fields.data(r).get("key")).distinct().toList();
  }

  private static List<Violation> eligibilityRespected(Ctx c) {
    Map<String, Object> eligibility =
        Fields.map(Fields.data(Tx.first(c.rs(), "tx_received")).get("eligibility"));
    if (eligibility.isEmpty()) {
      return List.of();
    }
    List<Map<String, Object>> used =
        c.rs().stream()
            .filter(
                r ->
                    Fields.oneOf(r.get("kind"), "quote_observed", "step_started", "provider_chosen")
                        && Fields.data(r).containsKey("provider"))
            .toList();
    List<Map<String, Object>> offenders =
        Fields.flag(eligibility.get("permitted?"))
            ? notAllowed(used, eligibility)
            : used.stream().limit(1).toList();
    return firstPerProvider(offenders).stream()
        .map(
            r ->
                violation(
                    Rule.ELIGIBILITY_RESPECTED,
                    c.id(),
                    r,
                    breachDetail(Fields.str(Fields.data(r).get("provider")), eligibility)))
        .toList();
  }

  private static List<Map<String, Object>> notAllowed(
      List<Map<String, Object>> used, Map<String, Object> eligibility) {
    List<String> allowed = Fields.strings(eligibility.get("allowed_providers"));
    return used.stream().filter(r -> !allowed.contains(Fields.data(r).get("provider"))).toList();
  }

  private static List<Map<String, Object>> firstPerProvider(List<Map<String, Object>> records) {
    Map<Object, Map<String, Object>> byProvider = new LinkedHashMap<>();
    for (Map<String, Object> record : records) {
      byProvider.putIfAbsent(Fields.data(record).get("provider"), record);
    }
    return new ArrayList<>(byProvider.values());
  }

  private static String breachDetail(String provider, Map<String, Object> eligibility) {
    if (Fields.flag(eligibility.get("permitted?"))) {
      return "%s was used but is not eligible for the %s corridor"
          .formatted(provider, String.join("/", Fields.strings(eligibility.get("regions"))));
    }
    return "%s was called although the transaction was not permitted (%s)"
        .formatted(provider, String.join("; ", Fields.strings(eligibility.get("reasons"))));
  }

  private static List<Violation> reviewRecorded(Ctx c) {
    boolean needed = !Tx.first(c.rs(), "needs_review").isEmpty();
    return Tx.all(c.rs(), "review_resolved").stream()
        .flatMap(
            r ->
                reviewProblem(Fields.data(r), needed)
                    .map(problem -> violation(Rule.REVIEW_RECORDED, c.id(), r, problem))
                    .stream())
        .toList();
  }

  private static Optional<String> reviewProblem(Map<String, Object> d, boolean needed) {
    if (!needed) {
      return Optional.of("a review was resolved for a transaction that never went to review");
    }
    return resolutionProblem(d).map(p -> "the recorded resolution is incomplete: " + p);
  }

  /** What is wrong with a resolution: an unknown outcome, or no name or reason. */
  private static Optional<String> resolutionProblem(Map<String, Object> d) {
    if (!Fields.oneOf(d.get("outcome"), "paid", "not_paid", "written_off")) {
      return Optional.of("choose one of: paid, not_paid, written_off");
    }
    return missing(d.get("by"), "say who is deciding")
        .or(() -> missing(d.get("note"), "say why: a decision without a reason cannot be audited"));
  }

  private static Optional<String> missing(Object text, String message) {
    return text instanceof String s && !s.trim().isEmpty()
        ? Optional.empty()
        : Optional.of(message);
  }

  private static List<Violation> selectionReproducible(Ctx c) {
    Map<String, Object> received = Tx.first(c.rs(), "tx_received");
    return Tx.rounds(c.rs()).stream()
        .filter(round -> !round.selection.isEmpty())
        .flatMap(round -> checkRound(c.id(), received, round).stream())
        .toList();
  }

  private static Optional<Violation> checkRound(
      String id, Map<String, Object> received, Tx.Round round) {
    Map<String, Object> selection = round.selection;
    List<Entry> recorded =
        Fields.list(Fields.data(selection).get("ranking")).stream()
            .map(item -> entryOf(Fields.map(item)))
            .toList();
    List<Entry> judged =
        Tx.reselectRound(received, round).ranked().stream()
            .map(candidate -> new Entry(candidate.providerId(), candidate.net()))
            .toList();
    if (judged.equals(recorded)) {
      return Optional.empty();
    }
    return Optional.of(
        violation(
            Rule.SELECTION_REPRODUCIBLE,
            id,
            selection,
            "recorded ranking %s but the recorded inputs give %s"
                .formatted(entries(recorded), entries(judged))));
  }

  private static Entry entryOf(Map<String, Object> item) {
    return new Entry(Fields.str(item.get("provider")), Fields.lng(item.get("net")));
  }

  private static String entries(List<Entry> entries) {
    return "["
        + entries.stream()
            .map(e -> "{\"" + e.provider() + "\", " + e.net() + "}")
            .reduce((a, b) -> a + ", " + b)
            .orElse("")
        + "]";
  }

  private static List<Violation> quoteValidAtAccept(Ctx c) {
    return Tx.all(c.rs(), "step_started").stream()
        .filter(r -> "accept_quote".equals(Fields.data(r).get("op")))
        .flatMap(r -> expiredAccept(c, r).stream())
        .toList();
  }

  private static Optional<Violation> expiredAccept(Ctx c, Map<String, Object> record) {
    String provider = Fields.str(Fields.data(record).get("provider"));
    long at = Fields.lngOr(record.get("at"), 0);
    return Tx.quoteFor(c.rs(), provider)
        .filter(quote -> at >= quote.expiresAtMs())
        .map(
            quote ->
                violation(
                    Rule.QUOTE_VALID_AT_ACCEPT,
                    c.id(),
                    record,
                    "accept asked of %s %dms after its quote expired"
                        .formatted(provider, at - quote.expiresAtMs())));
  }

  private static List<Violation> deadlineRespected(Ctx c) {
    Long deadline = Fields.lng(Fields.data(Tx.first(c.rs(), "deadline_set")).get("deadline_ms"));
    if (deadline == null) {
      return List.of();
    }
    long limit = deadline + c.tolerance();
    return Tx.all(c.rs(), "step_started").stream()
        .filter(r -> Fields.lngOr(r.get("at"), 0) > limit)
        .map(
            r ->
                violation(
                    Rule.DEADLINE_RESPECTED,
                    c.id(),
                    r,
                    "step began %dms after the cut-off"
                        .formatted(Fields.lngOr(r.get("at"), 0) - deadline)))
        .toList();
  }

  private static List<Violation> marginArithmetic(Ctx c) {
    if (c.summary().provider() == null) {
      return List.of();
    }
    return marginViolation(c).stream().toList();
  }

  private static Optional<Violation> marginViolation(Ctx c) {
    Map<String, Object> event =
        c.rs().stream()
            .filter(r -> Fields.oneOf(r.get("kind"), "guarantee_margin", "guarantee_exposure"))
            .findFirst()
            .orElse(Map.of());
    Map<String, Object> guarantee =
        Fields.map(Fields.data(Tx.first(c.rs(), "tx_received")).get("guarantee"));
    if (event.isEmpty() || guarantee.isEmpty()) {
      return Optional.empty();
    }
    long guaranteed =
        Money.convert(
            Fields.lngOr(c.summary().amount(), 0), Fields.lngOr(guarantee.get("rate"), 0));
    return Optional.ofNullable(Tx.netOf(c.rs(), c.summary().provider()))
        .flatMap(achieved -> marginCheck(c.id(), event, guaranteed, achieved));
  }

  private static Optional<Violation> marginCheck(
      String id, Map<String, Object> event, long guaranteed, long achieved) {
    Map<String, Object> d = Fields.data(event);
    boolean matches =
        Objects.equals(Fields.lng(d.get("guaranteed_net")), guaranteed)
            && Objects.equals(Fields.lng(d.get("achieved_net")), achieved)
            && Objects.equals(Fields.lng(d.get("margin")), achieved - guaranteed);
    if (matches) {
      return Optional.empty();
    }
    return Optional.of(
        violation(
            Rule.MARGIN_ARITHMETIC,
            id,
            event,
            "recorded margin %s, recomputed %d".formatted(d.get("margin"), achieved - guaranteed)));
  }

  private static List<Violation> economicsConsistent(Ctx c) {
    if (c.summary().outcome() != Outcome.COMPLETED) {
      return List.of();
    }
    Map<String, Object> economics = Tx.first(c.rs(), "tx_economics");
    Object chosen = Fields.dig(economics, "data", "discovery", "chosen_net");
    if (chosen == null) {
      return List.of();
    }
    Long net = Tx.netOf(c.rs(), c.summary().provider());
    if (Objects.equals(Fields.lng(chosen), net)) {
      return List.of();
    }
    return List.of(
        violation(
            Rule.ECONOMICS_CONSISTENT,
            c.id(),
            economics,
            "recorded chosen net %s, quote gives %s".formatted(chosen, Fields.inspect(net))));
  }

  private static List<Violation> timeOrdered(List<Map<String, Object>> records) {
    List<Violation> out = new ArrayList<>();
    for (int i = 1; i < records.size(); i++) {
      Map<String, Object> later = records.get(i);
      if (backwards(records.get(i - 1), later)) {
        out.add(
            violation(
                Rule.TIME_ORDERED,
                Fields.str(later.get("tx")),
                later,
                "time ran backwards at record " + later.get("n")));
      }
    }
    return out;
  }

  private static boolean backwards(Map<String, Object> earlier, Map<String, Object> later) {
    Long laterAt = Fields.lng(later.get("at"));
    Long earlierAt = Fields.lng(earlier.get("at"));
    return laterAt != null && earlierAt != null && laterAt < earlierAt;
  }

  private static Violation violation(
      Rule rule, String tx, Map<String, Object> record, String detail) {
    return new Violation(rule, tx, record.isEmpty() ? null : Fields.lng(record.get("n")), detail);
  }
}

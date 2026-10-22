package net.robbrown.actortx.selection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import net.robbrown.actortx.policy.Policy;
import net.robbrown.actortx.provider.Answer;
import net.robbrown.actortx.provider.FakeProvider;
import net.robbrown.actortx.provider.HealthConfig;
import net.robbrown.actortx.provider.HealthState;
import net.robbrown.actortx.provider.Limits;
import net.robbrown.actortx.provider.Mode;
import net.robbrown.actortx.provider.Operation;
import net.robbrown.actortx.provider.ProviderAdapter;
import net.robbrown.actortx.provider.ProviderServer;
import net.robbrown.actortx.provider.Quote;
import net.robbrown.actortx.provider.QuoteRequest;
import net.robbrown.actortx.selection.Selection.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Quote-and-select against real provider servers wrapping scriptable fakes. */
class QuoteSelectorTest {

  private record Heard(String kind, String message, Map<String, Object> data, String actor) {}

  private static final QuoteRequest REQUEST = QuoteRequest.of("fx-1", 10_000L);

  private final List<Heard> heard = new CopyOnWriteArrayList<>();
  private final List<ProviderServer> servers = new ArrayList<>();
  private final QuoteSelector selector =
      new QuoteSelector(
          (tx, actor, kind, message, data) -> heard.add(new Heard(kind, message, data, actor)));

  @AfterEach
  void tearDown() {
    servers.forEach(ProviderServer::close);
    selector.close();
  }

  private static ProviderServer serverOf(Participant participant) {
    return (ProviderServer) participant.source();
  }

  private Participant participant(FakeProvider fake) {
    return participant(fake, Limits.defaults(), null);
  }

  private Participant participant(FakeProvider fake, Limits limits, Set<String> currencies) {
    ProviderServer server =
        new ProviderServer(
            fake.id(),
            fake,
            HealthConfig.defaults().withFailureThreshold(2),
            limits,
            (tx, actor, kind, message, data) -> {},
            System::currentTimeMillis);
    servers.add(server);
    return new Participant(fake.id(), server, currencies);
  }

  private Selection select(
      Policy policy, long deadlineMs, String baseline, Participant... providers) {
    return selector.select(REQUEST, List.of(providers), policy, deadlineMs, baseline);
  }

  private Selection select(Participant... providers) {
    return select(Policies.costOnly(), 400, null, providers);
  }

  private List<String> messages() {
    return heard.stream().map(Heard::message).toList();
  }

  private static List<String> ranking(Selection selection) {
    return selection.ranked().stream().map(Candidate::providerId).toList();
  }

  @Test
  void aCostOnlyPolicySelectsTheHighestNetValue() {
    Selection selection =
        select(
            participant(new FakeProvider("cheap").rate("1.00")),
            participant(new FakeProvider("best").rate("1.10")),
            participant(new FakeProvider("mid").rate("1.05")));

    assertThat(ranking(selection)).containsExactly("best", "mid", "cheap");
    assertThat(selection.outcome()).isInstanceOf(Outcome.Selected.class);
    assertThat(((Outcome.Selected) selection.outcome()).best().net()).isEqualTo(11_000L);
    assertThat(selection.excluded()).isEmpty();
  }

  @Test
  void stalledAndQuarantinedProvidersAreSkippedWithoutBeingCalled() {
    FakeProvider flaky =
        new FakeProvider("flaky").rate("1.20").script(Operation.REQUEST_QUOTE, Mode.error("down"));
    FakeProvider drifter = new FakeProvider("drifter").rate("1.30");
    FakeProvider good = new FakeProvider("good").rate("1.05");
    Participant flakyP = participant(flaky);
    Participant drifterP = participant(drifter);
    serverOf(flakyP).requestQuote(REQUEST, Duration.ofSeconds(1));
    serverOf(flakyP).requestQuote(REQUEST, Duration.ofSeconds(1));
    serverOf(drifterP).probe(Duration.ofSeconds(1));
    drifter.version("2");
    serverOf(drifterP).probe(Duration.ofSeconds(1));
    assertThat(serverOf(flakyP).state()).isEqualTo(HealthState.STALLED);
    assertThat(serverOf(drifterP).state()).isEqualTo(HealthState.QUARANTINED);
    long flakyCalls = flaky.calls().get(Operation.REQUEST_QUOTE);
    long drifterCalls =
        drifter.calls().get(Operation.REQUEST_QUOTE) == null
            ? 0
            : drifter.calls().get(Operation.REQUEST_QUOTE);

    Selection selection = select(flakyP, drifterP, participant(good));

    assertThat(ranking(selection)).containsExactly("good");
    assertThat(selection.excluded())
        .contains(
            new Excluded("flaky", "state: stalled"), new Excluded("drifter", "state: quarantined"));
    assertThat(flaky.calls().get(Operation.REQUEST_QUOTE)).isEqualTo((int) flakyCalls);
    assertThat(drifter.calls().getOrDefault(Operation.REQUEST_QUOTE, 0))
        .isEqualTo((int) drifterCalls);
  }

  @Test
  void theNarrativeSaysWhoWasNotCalledAndWhoWonAheadOfWhom() {
    FakeProvider stalled =
        new FakeProvider("stalled").script(Operation.REQUEST_QUOTE, Mode.error("down"));
    Participant stalledP = participant(stalled);
    serverOf(stalledP).requestQuote(REQUEST, Duration.ofSeconds(1));
    serverOf(stalledP).requestQuote(REQUEST, Duration.ofSeconds(1));

    select(
        stalledP,
        participant(new FakeProvider("alpha").rate("1.10")),
        participant(new FakeProvider("beta").rate("1.05")));

    assertThat(messages())
        .contains(
            "asking 3 provider(s) for quotes (deadline 400ms): stalled, alpha, beta",
            "not calling stalled: it is stalled",
            "alpha quoted rate 1.1000, fee 0.00 (net 110.00, 0ms)"
                .replace("0ms", heardLatency("alpha") + "ms"));
    assertThat(
            messages().stream()
                .filter(m -> m.startsWith("selected alpha (score 1.0, net 110.00,"))
                .findFirst())
        .get()
        .asString()
        .endsWith(" ahead of beta (0.0)");
  }

  private String heardLatency(String provider) {
    return heard.stream()
        .filter(h -> h.kind().equals("quote_received") && h.data().get("provider").equals(provider))
        .map(h -> String.valueOf(h.data().get("latency_ms")))
        .findFirst()
        .orElse("?");
  }

  @Test
  void rejectionsErrorsAndTimeoutsAreReportedAndTheDeadlineIsAHardBound() {
    FakeProvider slow = new FakeProvider("slow").latencyMs(2_000);
    Selection selection =
        select(
            Policies.costOnly(),
            100,
            null,
            participant(new FakeProvider("ok").rate("1.05")),
            participant(new FakeProvider("no").script(Operation.REQUEST_QUOTE, Mode.REJECT)),
            participant(
                new FakeProvider("boom").script(Operation.REQUEST_QUOTE, Mode.error("down"))),
            participant(slow));

    assertThat(ranking(selection)).containsExactly("ok");
    assertThat(selection.excluded())
        .contains(
            new Excluded("no", "rejected: no_liquidity"),
            new Excluded("boom", "error: down"),
            new Excluded("slow", "timeout"));
    assertThat(selection.elapsedMs()).isLessThan(1_000);
  }

  @Test
  void noUsableQuoteGivesNoneOrTheCallersBaseline() {
    Participant down =
        participant(new FakeProvider("down").script(Operation.REQUEST_QUOTE, Mode.TIMEOUT));

    assertThat(select(Policies.costOnly(), 200, null, down).outcome())
        .isEqualTo(new Outcome.None());
    Selection fallback =
        select(
            Policies.costOnly(),
            200,
            "baseline-route",
            participant(new FakeProvider("rej").script(Operation.REQUEST_QUOTE, Mode.REJECT)));
    assertThat(fallback.outcome()).isEqualTo(new Outcome.Fallback("baseline-route"));
    assertThat(messages())
        .contains(
            "no usable quote from any provider",
            "no usable quote; falling back to baseline baseline-route");
  }

  @Test
  void everyAnswerIsJournaledForReplay() {
    select(
        participant(new FakeProvider("q").rate("1.10")),
        participant(new FakeProvider("r").script(Operation.REQUEST_QUOTE, Mode.REJECT)),
        participant(new FakeProvider("e").script(Operation.REQUEST_QUOTE, Mode.error("down"))),
        participant(new FakeProvider("t").script(Operation.REQUEST_QUOTE, Mode.TIMEOUT)));

    List<Heard> journal = heard.stream().filter(h -> h.kind().equals("quote_observed")).toList();
    assertThat(journal).hasSize(4).allSatisfy(h -> assertThat(h.actor()).isEqualTo("journal"));
    Map<Object, Object> outcomes = new java.util.HashMap<>();
    journal.forEach(h -> outcomes.put(h.data().get("provider"), h.data().get("outcome")));
    assertThat(outcomes)
        .containsEntry("q", "quote")
        .containsEntry("r", "rejected")
        .containsEntry("e", "error")
        .containsEntry("t", "timeout");
    Heard quoted =
        journal.stream()
            .filter(h -> h.data().get("provider").equals("q"))
            .findFirst()
            .orElseThrow();
    assertThat(quoted.data())
        .containsEntry("rate", 110_000_000L)
        .containsEntry("request_id", "fx-1")
        .containsEntry("state", "unknown")
        .containsKeys("fee", "expires_at_ms", "latency_ms", "observed_at_ms");
    assertThat(messages()).contains("journal: r answered the quote request (rejected)");
  }

  /**
   * A provider whose server answers nothing for a long time cannot hold the selection past the
   * deadline.
   */
  @Test
  void aHungProviderCannotHoldTheSelectionPastTheDeadline() throws InterruptedException {
    CountDownLatch never = new CountDownLatch(1);
    ProviderAdapter hung = hungAdapter(never);
    ProviderServer hungServer = new ProviderServer("hung", hung);
    servers.add(hungServer);

    Selection selection =
        select(
            Policies.costOnly(),
            100,
            null,
            new Participant("hung", hungServer, null),
            participant(new FakeProvider("ok").rate("1.05")));

    assertThat(ranking(selection)).containsExactly("ok");
    assertThat(selection.excluded()).contains(new Excluded("hung", "timeout"));
    assertThat(selection.elapsedMs()).isLessThan(2_000);
    never.countDown();
  }

  private static ProviderAdapter hungAdapter(CountDownLatch never) {
    return new FakeProvider("hung") {
      @Override
      public Answer<Quote> requestQuote(QuoteRequest request) {
        try {
          never.await();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
        return new Answer.Timeout<>();
      }
    };
  }

  @Test
  void aProviderWhoseBulkheadIsFullIsAskedAgainUntilASlotFrees() throws InterruptedException {
    FakeProvider busy = new FakeProvider("busy").rate("1.10");
    Participant busyP = participant(busy, Limits.defaults().withMaxInFlight(1), null);
    CountDownLatch free = new CountDownLatch(1);
    Thread holder = occupy(busyP, free);
    Thread.ofVirtual().start(() -> sleepThen(free, 60));

    Selection selection = select(Policies.costOnly(), 500, null, busyP);

    assertThat(ranking(selection)).containsExactly("busy");
    assertThat(selection.elapsedMs()).isGreaterThanOrEqualTo(40);
    holder.join(1_000);
  }

  @Test
  void aProviderThatStaysBusyToTheDeadlineIsReportedAsOverloaded() {
    Participant busyP =
        participant(new FakeProvider("busy"), Limits.defaults().withMaxInFlight(1), null);
    CountDownLatch free = new CountDownLatch(1);
    occupy(busyP, free);

    Selection selection = select(Policies.costOnly(), 100, null, busyP);

    assertThat(selection.outcome()).isEqualTo(new Outcome.None());
    assertThat(selection.excluded()).containsExactly(new Excluded("busy", "error: overloaded"));
    free.countDown();
  }

  /** Holds the provider's only slot with a call that blocks until released. */
  private Thread occupy(Participant participant, CountDownLatch release) {
    Thread thread =
        Thread.ofVirtual()
            .start(
                () ->
                    serverOf(participant)
                        .invoke(
                            Operation.REQUEST_QUOTE,
                            a -> blockedQuote(release),
                            Duration.ofSeconds(5)));
    await().atMost(Duration.ofSeconds(1)).until(() -> serverOf(participant).inFlight() == 1);
    return thread;
  }

  private static Answer<Quote> blockedQuote(CountDownLatch release) {
    awaitLatch(release);
    return new Answer.Error<>("released");
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private static void sleepThen(CountDownLatch latch, long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    latch.countDown();
  }

  @Test
  void aProviderThatDoesNotCarryThePairIsNotAskedAndIsReported() {
    FakeProvider india = new FakeProvider("india").rate("1.0");
    FakeProvider europe = new FakeProvider("europe").rate("2.0");
    QuoteRequest request = new QuoteRequest("fx-2", 10_000L, "USD", "INR");

    Selection selection =
        selector.select(
            request,
            List.of(
                participant(india, Limits.defaults(), Set.of("USD", "INR")),
                participant(europe, Limits.defaults(), Set.of("USD", "EUR"))),
            Policies.costOnly(),
            400,
            null);

    assertThat(ranking(selection)).containsExactly("india");
    assertThat(selection.excluded()).contains(new Excluded("europe", "unsupported: USD/INR"));
    assertThat(europe.calls()).isEmpty();
    assertThat(heard)
        .anySatisfy(
            h ->
                assertThat(h.message())
                    .isEqualTo("not calling europe: it does not carry USD to INR"));
  }

  @Test
  void whenNoProviderCarriesThePairThereIsNoQuote() {
    QuoteRequest request = new QuoteRequest("fx-3", 10_000L, "TRY", "KRW");

    Selection selection =
        selector.select(
            request,
            List.of(
                participant(new FakeProvider("narrow"), Limits.defaults(), Set.of("USD", "EUR"))),
            Policies.costOnly(),
            200,
            null);

    assertThat(selection.outcome()).isEqualTo(new Outcome.None());
    assertThat(selection.excluded())
        .containsExactly(new Excluded("narrow", "unsupported: TRY/KRW"));
  }

  @Test
  void aSelectionWithNoAmountPricesAtZero() {
    Selection selection =
        selector.select(
            QuoteRequest.of("fx-4", null),
            List.of(participant(new FakeProvider("p").rate("1.1"))),
            Policies.costOnly(),
            200,
            null);

    assertThat(ranking(selection)).containsExactly("p");
    assertThat(selection.ranked().get(0).net()).isZero();
  }

  @Test
  void quotesThatBreakAHardConstraintAndFailedQuotesAreNarrated() {
    Policy fastOnly = Policies.of(Map.of("weights", Map.of("cost", 1), "max_latency_ms", 100));

    select(
        fastOnly,
        400,
        null,
        participant(new FakeProvider("slowpoke").latencyMs(150)),
        participant(new FakeProvider("no").script(Operation.REQUEST_QUOTE, Mode.REJECT)));

    assertThat(messages()).contains("slowpoke's quote is unusable: too_slow");
    assertThat(messages())
        .anyMatch(m -> m.startsWith("no gave no quote: rejected: no_liquidity after "));
  }

  @Test
  void aParticipantCanBeBuiltWithoutCurrencies() {
    Participant open = Participant.of("open", serverOf(participant(new FakeProvider("open"))));

    assertThat(open.currencies()).isNull();
    assertThat(open.carries("USD", "INR")).isTrue();
  }
}

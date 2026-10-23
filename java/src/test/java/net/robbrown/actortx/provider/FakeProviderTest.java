package net.robbrown.actortx.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.robbrown.actortx.provider.Answer.Error;
import net.robbrown.actortx.provider.Answer.InDoubt;
import net.robbrown.actortx.provider.Answer.NotFound;
import net.robbrown.actortx.provider.Answer.Ok;
import net.robbrown.actortx.provider.Answer.Rejected;
import net.robbrown.actortx.provider.Answer.Timeout;
import net.robbrown.actortx.provider.Answer.Unknown;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FakeProviderTest {

  private record Heard(
      String tx, String actor, String kind, String message, Map<String, Object> data) {}

  private final List<Heard> heard = new ArrayList<>();
  private FakeProvider fake;

  @BeforeEach
  void setUp() {
    fake =
        new FakeProvider(
                "f",
                (tx, actor, kind, message, data) ->
                    heard.add(new Heard(tx, actor, kind, message, data)))
            .rate("1.1")
            .fee("2.0");
  }

  private Quote quoted() {
    return ((Ok<Quote>) fake.requestQuote(QuoteRequest.of("r1", null))).value();
  }

  @Test
  void quotesCarryRateFeeAndAFutureExpiry() {
    Quote quote = quoted();

    assertThat(quote.provider()).isEqualTo("f");
    assertThat(quote.requestId()).isEqualTo("r1");
    assertThat(quote.rate()).isEqualTo(110_000_000L);
    assertThat(quote.fee()).isEqualTo(200L);
    assertThat(quote.expired(System.currentTimeMillis())).isFalse();
  }

  @Test
  void settingsAreHeldExactlyAndReadBack() {
    fake.rate("1.25").fee("0.5").latencyMs(7);
    assertThat(fake.currentRate()).isEqualTo(125_000_000L);
    assertThat(fake.currentFee()).isEqualTo(50L);
    assertThat(fake.currentLatencyMs()).isEqualTo(7L);
    assertThat(fake.rateScaled(130_000_000L).currentRate()).isEqualTo(130_000_000L);
    assertThat(fake.id()).isEqualTo("f");
  }

  @Test
  void quoteModes() {
    fake.script(Operation.REQUEST_QUOTE, Mode.REJECT);
    assertThat(fake.requestQuote(QuoteRequest.of("r", null)))
        .isEqualTo(new Rejected<Quote>("no_liquidity"));
    fake.script(Operation.REQUEST_QUOTE, Mode.TIMEOUT);
    assertThat(fake.requestQuote(QuoteRequest.of("r", null))).isEqualTo(new Timeout<Quote>());
    fake.script(Operation.REQUEST_QUOTE, Mode.error("boom"));
    assertThat(fake.requestQuote(QuoteRequest.of("r", null))).isEqualTo(new Error<Quote>("boom"));
  }

  @Test
  void latencyIsSimulated() {
    fake.latencyMs(20);
    long start = System.nanoTime();
    fake.probe();
    assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(20);
  }

  @Test
  void aSleepingProviderThatIsInterruptedStopsWaitingAndKeepsTheFlag() {
    fake.latencyMs(5_000);
    Thread.currentThread().interrupt();
    fake.probe();
    assertThat(Thread.interrupted()).isTrue();
  }

  @Test
  void eachLifecycleOperationIsAnIdempotentRecordedEffect() {
    Quote quote = quoted();
    List<Operation> withQuote = List.of(Operation.ACCEPT_QUOTE, Operation.AUTHORISE, Operation.PAY);

    Answer<Receipt> first = fake.acceptQuote(quote, "tx:k");
    assertThat(fake.acceptQuote(quote, "tx:k")).isEqualTo(first);
    assertThat(fake.authorise(quote, "tx:k")).isInstanceOf(Ok.class);
    assertThat(fake.authorise(quote, "tx:k")).isInstanceOf(Ok.class);
    assertThat(fake.pay(quote, "tx:k")).isInstanceOf(Ok.class);
    assertThat(fake.pay(quote, "tx:k")).isInstanceOf(Ok.class);
    assertThat(fake.settle("tx:k")).isInstanceOf(Ok.class);

    assertThat(withQuote).allSatisfy(op -> assertThat(fake.effectCount(op)).isEqualTo(1));
    assertThat(fake.effectCount(Operation.SETTLE)).isEqualTo(1);
    assertThat(fake.calls().get(Operation.PAY)).isEqualTo(2);
  }

  @Test
  void undosAreIdempotentToo() {
    assertThat(fake.cancelAccept("tx:k")).isInstanceOf(Ok.class);
    assertThat(fake.cancelAccept("tx:k")).isInstanceOf(Ok.class);
    assertThat(fake.voidAuthorisation("tx:k")).isInstanceOf(Ok.class);
    assertThat(fake.refund("tx:k")).isInstanceOf(Ok.class);
    assertThat(fake.refund("tx:k")).isInstanceOf(Ok.class);

    assertThat(fake.effectCount(Operation.CANCEL_ACCEPT)).isEqualTo(1);
    assertThat(fake.effectCount(Operation.REFUND)).isEqualTo(1);
    assertThat(fake.effects()).hasSize(3).containsKey("refund|tx:k");
  }

  @Test
  void aReceiptRemembersTheQuoteItWasMadeUnder() {
    Receipt receipt = ((Ok<Receipt>) fake.pay(quoted(), "tx:k")).value();
    assertThat(receipt).isEqualTo(new Receipt("f", Operation.PAY, "tx:k", 110_000_000L, 200L));

    Receipt undo = ((Ok<Receipt>) fake.refund("tx:k")).value();
    assertThat(undo.rate()).isNull();
    assertThat(undo.fee()).isNull();
  }

  @Test
  void failureModesDoNotPerformTheEffect() {
    Quote quote = quoted();
    fake.script(Operation.PAY, Mode.TIMEOUT);
    assertThat(fake.pay(quote, "t:k")).isEqualTo(new Timeout<Receipt>());
    fake.script(Operation.PAY, Mode.error("down"));
    assertThat(fake.pay(quote, "t:k")).isEqualTo(new Error<Receipt>("down"));
    fake.script(Operation.PAY, Mode.REJECT);
    assertThat(fake.pay(quote, "t:k")).isEqualTo(new Rejected<Receipt>("declined"));
    assertThat(fake.effectCount(Operation.PAY)).isZero();
  }

  @Test
  void anExpiredQuoteIsRejected() {
    fake.quoteTtlMs(-1);
    Quote quote = quoted();
    assertThat(fake.acceptQuote(quote, "t:k")).isEqualTo(new Rejected<Receipt>("quote_expired"));
  }

  @Test
  void inDoubtPerformsTheEffectButLosesTheAnswerAndStatusResolvesIt() {
    Quote quote = quoted();
    fake.script(Operation.PAY, Mode.IN_DOUBT);

    assertThat(fake.pay(quote, "t:k")).isEqualTo(new InDoubt<Receipt>());
    assertThat(((Ok<Receipt>) fake.status(Operation.PAY, "t:k")).value().key()).isEqualTo("t:k");
    assertThat(fake.status(Operation.PAY, "t:other")).isEqualTo(new NotFound<Receipt>());
    assertThat(fake.status(Operation.REFUND, "t:k")).isEqualTo(new NotFound<Receipt>());
  }

  /** Regression: a doubtful payment retried with the same key must not be paid twice. */
  @Test
  void inDoubtRetriedWithTheSameKeyDoesNotDuplicateThePayment() {
    Quote quote = quoted();
    fake.script(Operation.PAY, Mode.IN_DOUBT);
    fake.pay(quote, "t:k");
    fake.pay(quote, "t:k");
    assertThat(fake.effectCount(Operation.PAY)).isEqualTo(1);
  }

  @Test
  void scriptedSequencesAreConsumedOnePerCallAndTheLastSticks() {
    Quote quote = quoted();
    fake.script(Operation.PAY, Mode.TIMEOUT, Mode.error("x"), Mode.OK);

    assertThat(fake.pay(quote, "t:k")).isEqualTo(new Timeout<Receipt>());
    assertThat(fake.pay(quote, "t:k")).isEqualTo(new Error<Receipt>("x"));
    assertThat(fake.pay(quote, "t:k")).isInstanceOf(Ok.class);
    assertThat(fake.pay(quote, "t:k")).isInstanceOf(Ok.class);
  }

  @Test
  void statusModes() {
    fake.script(Operation.STATUS, Mode.UNKNOWN);
    assertThat(fake.status(Operation.PAY, "t:k")).isEqualTo(new Unknown<Receipt>());
    fake.script(Operation.STATUS, Mode.TIMEOUT);
    assertThat(fake.status(Operation.PAY, "t:k")).isEqualTo(new Timeout<Receipt>());
  }

  @Test
  void probeReportsTheVersionAndCanFail() {
    assertThat(fake.probe()).isEqualTo(new Ok<>(new ProbeInfo("1", 0)));
    fake.version("2");
    assertThat(((Ok<ProbeInfo>) fake.probe()).value().version()).isEqualTo("2");
    fake.script(Operation.PROBE, Mode.error("gone"));
    assertThat(fake.probe()).isEqualTo(new Error<ProbeInfo>("gone"));
  }

  @Test
  void theFakeNarratesReceivingProcessingAndResponding() {
    fake.latencyMs(5);
    Quote quote =
        ((Ok<Quote>) fake.requestQuote(new QuoteRequest("story-1", 100L, null, null))).value();
    fake.acceptQuote(quote, "story-1:f:accept");

    assertThat(heard)
        .anySatisfy(
            h ->
                assertThat(h)
                    .extracting(Heard::kind, Heard::tx, Heard::actor)
                    .containsExactly("received", "story-1", "provider:f"))
        .anySatisfy(h -> assertThat(h.message()).isEqualTo("processing (takes ~5ms)"))
        .anySatisfy(
            h ->
                assertThat(h.data())
                    .containsEntry("op", "request_quote")
                    .containsEntry("result", "ok"))
        .anySatisfy(
            h ->
                assertThat(h.data())
                    .containsEntry("op", "accept_quote")
                    .containsEntry("result", "ok"));
    assertThat(heard.get(0).message()).isEqualTo("received a quote request for 1.00");
  }

  @Test
  void aRequestWithNoAmountIsNarratedWithoutOne() {
    fake.requestQuote(QuoteRequest.of("a", null));
    assertThat(heard.get(0).message())
        .isEqualTo("received a quote request for an unspecified amount");
  }

  @Test
  void responsesOfEveryShapeAreDescribed() {
    fake.script(Operation.REQUEST_QUOTE, Mode.REJECT);
    fake.requestQuote(QuoteRequest.of("d1", null));
    fake.script(Operation.REQUEST_QUOTE, Mode.error("boom"));
    fake.requestQuote(QuoteRequest.of("d1", null));
    fake.script(Operation.REQUEST_QUOTE, Mode.TIMEOUT);
    fake.requestQuote(QuoteRequest.of("d1", null));
    fake.script(Operation.REQUEST_QUOTE, Mode.OK);
    fake.requestQuote(QuoteRequest.of("d1", null));
    fake.settle("t:k");

    List<String> messages = heard.stream().map(Heard::message).toList();
    assertThat(messages)
        .contains(
            "request_quote -> rejected (no_liquidity)",
            "request_quote -> error (boom)",
            "request_quote -> timeout",
            "request_quote -> quote rate 1.1000 fee 2.00",
            "settle -> ok");
  }

  @Test
  void aProbeIsNarratedAgainstTheSystemNotATransaction() {
    fake.probe();
    assertThat(heard.get(0).tx()).isNull();
  }

  @Test
  void aFakeWithoutASinkStillWorks() {
    assertThat(new FakeProvider("quiet").probe()).isInstanceOf(Ok.class);
  }

  @Test
  void aKnownPairIsQuotedAtTheMarketRateThroughTheProvidersOwnSpread() {
    fake.rate("1.08").pairs(Map.of("USD/EUR", 92_590_000L), 108_000_000L);
    QuoteRequest pair = new QuoteRequest("r1", null, "USD", "EUR");

    assertThat(((Ok<Quote>) fake.requestQuote(pair)).value().rate()).isEqualTo(92_590_000L);

    // a provider that quotes 1% better than the reference gives 1% better on every pair
    fake.rate("1.0908");
    assertThat(((Ok<Quote>) fake.requestQuote(pair)).value().rate()).isEqualTo(93_515_900L);
  }

  @Test
  void anUnknownPairOrARequestWithoutCurrenciesIsQuotedAtThePlainRate() {
    fake.pairs(Map.of("USD/EUR", 92_590_000L), 108_000_000L);

    assertThat(((Ok<Quote>) fake.requestQuote(QuoteRequest.of("r", null))).value().rate())
        .isEqualTo(110_000_000L);
    assertThat(
            ((Ok<Quote>) fake.requestQuote(new QuoteRequest("r", null, "USD", "JPY")))
                .value()
                .rate())
        .isEqualTo(110_000_000L);
    assertThat(new QuoteRequest("r", null, "USD", null).pair()).isNull();
  }

  @Test
  void callsStartEmpty() {
    assertThat(new FakeProvider("fresh").calls()).isEmpty();
  }

  @Test
  void resettingScriptsMakesEveryOperationBehaveAgain() {
    fake.script(Operation.PAY, Mode.TIMEOUT).script(Operation.REQUEST_QUOTE, Mode.REJECT);

    fake.resetScripts();

    assertThat(fake.requestQuote(QuoteRequest.of("r", null))).isInstanceOf(Ok.class);
    assertThat(fake.pay(quoted(), "t:k")).isInstanceOf(Ok.class);
  }

  @Test
  void faultsListWhatEachOperationWillDoInsteadOfBehaving() {
    FakeProvider fake = new FakeProvider("p");
    fake.script(Operation.SETTLE, Mode.REJECT);
    fake.script(Operation.PAY, Mode.OK);
    fake.script(Operation.AUTHORISE, Mode.error("down"), Mode.OK);

    assertThat(fake.faults())
        .containsOnlyKeys(Operation.AUTHORISE, Operation.SETTLE)
        .containsEntry(Operation.SETTLE, Mode.REJECT);
    fake.resetScripts();
    assertThat(fake.faults()).isEmpty();
  }
}

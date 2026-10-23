package net.robbrown.actortx.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import net.robbrown.actortx.eligibility.Eligibility;
import net.robbrown.actortx.events.EventSink;
import net.robbrown.actortx.payment.Payment.Routing;
import net.robbrown.actortx.payment.Result.Completed;
import net.robbrown.actortx.payment.Result.NeedsReview;
import net.robbrown.actortx.policy.Policy;
import net.robbrown.actortx.provider.FakeProvider;
import net.robbrown.actortx.provider.Mode;
import net.robbrown.actortx.provider.Operation;
import net.robbrown.actortx.provider.ProviderServer;
import net.robbrown.actortx.selection.Participant;
import net.robbrown.actortx.selection.QuoteSelector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The payment engine runs whatever workflow it is given. These tests change the workflow, the
 * providers' capabilities and the way a step completes, without touching the engine: that is the
 * point of the design.
 */
class WorkflowTest {

  private static final Operation SCREEN =
      new Operation("screen", net.robbrown.actortx.provider.BulkheadClass.STEP);
  private static final Operation RELEASE =
      new Operation("release_screen", net.robbrown.actortx.provider.BulkheadClass.PROTECT);
  private static final Step SCREENING =
      Steps.reversible(
          SCREEN,
          "screen",
          "screen the payment",
          new Compensation(RELEASE, "release the screening"));

  private final List<String> kinds = new CopyOnWriteArrayList<>();
  private final EventSink sink = (tx, actor, kind, message, data) -> kinds.add(kind);
  private final QuoteSelector selector = new QuoteSelector(sink);
  private FakeProvider north;
  private FakeProvider cobalt;
  private ProviderServer northServer;
  private ProviderServer cobaltServer;
  private PaymentOptions options;

  @BeforeEach
  void setUp() {
    north = new FakeProvider("northwind").rate("1.10");
    cobalt = new FakeProvider("cobalt").rate("1.00");
    northServer = new ProviderServer("northwind", north);
    cobaltServer = new ProviderServer("cobalt", cobaltServerAdapter());
    options =
        PaymentOptions.defaults()
            .withStepTimeoutMs(300)
            .withBackoffMs(1)
            .withReconcileIntervalMs(1);
  }

  private FakeProvider cobaltServerAdapter() {
    return cobalt;
  }

  @AfterEach
  void tearDown() {
    northServer.close();
    cobaltServer.close();
    selector.close();
  }

  private Result run(TransactionRequest request, Workflows workflows) {
    Routing routing =
        new Routing(
            List.of(
                Participant.of("northwind", northServer), Participant.of("cobalt", cobaltServer)),
            Eligibility.open(),
            Policy.defaults(),
            "");
    PaymentServices services = PaymentServices.of(selector, sink).withWorkflows(workflows);
    return new Payment(request, routing, options, services)
        .start()
        .await(Duration.ofSeconds(10))
        .orElseThrow();
  }

  private static Workflows screened() {
    return (request, provider) -> {
      List<Step> steps = new ArrayList<>(Workflow.standard().steps());
      steps.add(0, SCREENING);
      return new Workflow(steps);
    };
  }

  private static List<String> steps(Result result) {
    return ((Completed) result).summary().steps();
  }

  @Test
  void anExtraStepJoinsThePaymentWithoutTheEngineChanging() {
    Result result = run(TransactionRequest.of("t1", 100_000), screened());

    assertThat(steps(result))
        .containsExactly("screen", "accept_quote", "authorise", "pay", "settle");
    assertThat(north.effectCount(SCREEN)).isEqualTo(1);
  }

  @Test
  void aFailedExtraStepIsUndoneLikeAnyOther() {
    north.script(Operation.AUTHORISE, Mode.REJECT);

    Result result = run(TransactionRequest.of("t2", 100_000), screened());

    assertThat(((Completed) result).summary().provider()).isEqualTo("cobalt");
    assertThat(north.effectCount(RELEASE)).isEqualTo(1);
    assertThat(north.effectCount(Operation.CANCEL_ACCEPT)).isEqualTo(1);
  }

  @Test
  void eachCorridorCanHaveItsOwnWorkflow() {
    Workflows byCorridor =
        (request, provider) ->
            "IN".equals(request.destinationRegion())
                ? screened().forRoute(request, provider)
                : Workflow.standard();

    Result domestic = run(TransactionRequest.of("t3", 100_000), byCorridor);
    Result india =
        run(TransactionRequest.builder("t4", 100_000).destination("IN").build(), byCorridor);

    assertThat(steps(domestic)).doesNotContain("screen");
    assertThat(steps(india)).startsWith("screen");
  }

  @Test
  void aProviderThatCannotDoAStepIsPassedOver() {
    north.without(Operation.AUTHORISE);

    Result result = run(TransactionRequest.of("t5", 100_000), Workflows.STANDARD);

    assertThat(((Completed) result).summary().provider()).isEqualTo("cobalt");
    assertThat(kinds).contains("unsupported_step");
    assertThat(north.calls()).doesNotContainKey(Operation.ACCEPT_QUOTE);
  }

  @Test
  void aProviderThatLacksTheExtraStepIsPassedOverToo() {
    north.without(SCREEN);

    Result result = run(TransactionRequest.of("t6", 100_000), screened());

    assertThat(((Completed) result).summary().provider()).isEqualTo("cobalt");
  }

  private static Workflows asyncSettlement() {
    Step async =
        new Steps.Declared(
            Operation.SETTLE,
            "settle",
            "settle the payment",
            Phase.running("settling"),
            "settlement",
            Optional.empty(),
            true);
    return (request, provider) ->
        new Workflow(List.of(Steps.ACCEPT_QUOTE, Steps.AUTHORISE, Steps.PAY, async));
  }

  @Test
  void anAsynchronousStepIsConfirmedByAskingTheProvider() {
    Result result = run(TransactionRequest.of("t7", 100_000), asyncSettlement());

    assertThat(steps(result)).endsWith("settle");
    assertThat(north.calls()).containsKey(Operation.STATUS);
    assertThat(kinds).contains("reconciled");
  }

  @Test
  void anAsynchronousStepThatIsNeverConfirmedGoesToAPerson() {
    north.script(Operation.STATUS, Mode.UNKNOWN);

    Result result = run(TransactionRequest.of("t8", 100_000), asyncSettlement());

    assertThat(result).isEqualTo(new NeedsReview("in_doubt: settle"));
  }

  /** A step that overrides only what it must. */
  private static final class BareStep implements Step {
    @Override
    public Operation operation() {
      return Operation.PAY;
    }

    @Override
    public String keyName() {
      return "pay";
    }

    @Override
    public String verb() {
      return "pay";
    }

    @Override
    public Phase phase() {
      return Phase.running("paying");
    }

    @Override
    public String label() {
      return "pay";
    }

    @Override
    public Optional<Compensation> compensation() {
      return Optional.empty();
    }
  }

  @Test
  void aStepThatSaysNothingExtraIsSynchronousAndIrreversibleByDefault() {
    Step bare = new BareStep();

    assertThat(bare.confirmedByStatus()).isFalse();
    assertThat(bare.reversible()).isFalse();
    assertThat(Phase.ended(Outcome.NEEDS_REVIEW)).isEqualTo(Phase.NEEDS_REVIEW);
  }
}

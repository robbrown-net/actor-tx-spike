package net.robbrown.actortx.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;
import net.robbrown.actortx.events.EventSink;
import net.robbrown.actortx.provider.FakeProvider;
import net.robbrown.actortx.provider.Mode;
import net.robbrown.actortx.provider.Operation;
import net.robbrown.actortx.selection.QuoteSelector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SelectionServiceTest {

  private StubCatalog catalog;
  private QuoteSelector selector;
  private SelectionService service;

  @BeforeEach
  void setUp() {
    catalog =
        new StubCatalog(
            new FakeProvider("cheap").rate("1.00"),
            new FakeProvider("best").rate("1.10").fee("2.00"));
    selector = new QuoteSelector(EventSink.NONE);
    service = new SelectionService(catalog, selector, null);
  }

  @AfterEach
  void tearDown() {
    selector.close();
    catalog.close();
  }

  private static SelectCommand command(BigDecimal amount, Map<String, Object> policy) {
    return new SelectCommand(amount, null, null, null, null, policy, 400L, null);
  }

  @Test
  void quotesAndSelectsUnderTheDefaultPolicyConvertingMajorToMinorUnits() {
    SelectionView view = service.select(command(new BigDecimal("1000.00"), null));

    assertThat(view.outcome()).isEqualTo("selected");
    assertThat(view.provider()).isEqualTo("best");
    assertThat(view.ranked())
        .extracting(SelectionView.RankedView::provider)
        .containsExactly("best", "cheap");
    // 1000.00 * 1.10 - 2.00 fee
    assertThat(view.ranked().get(0).net()).isEqualTo("1098.00");
    assertThat(view.ranked().get(0).netMinor()).isEqualTo(109_800L);
    assertThat(view.ranked().get(0).rate()).isEqualTo("1.1000");
    assertThat(view.ranked().get(0).fee()).isEqualTo("2.00");
    assertThat(view.excluded()).isEmpty();
  }

  @Test
  void aPolicyGivenAsPlainDataIsValidatedAndApplied() {
    SelectionView view =
        service.select(
            command(
                new BigDecimal("100"),
                Map.of("weights", Map.of("cost", 1), "max_bps_from_best", 100)));

    assertThat(view.provider()).isEqualTo("best");
    assertThat(view.excluded())
        .extracting(e -> e.reason())
        .singleElement()
        .asString()
        .startsWith("worse_than_best_bps");
  }

  @Test
  void anInvalidPolicyIsRefusedWithEveryProblem() {
    assertThatThrownBy(
            () -> service.select(command(BigDecimal.ONE, Map.of("weights", "x", "bogus", 1))))
        .isInstanceOf(InvalidInputException.class)
        .satisfies(e -> assertThat(((InvalidInputException) e).errors()).hasSize(2));
  }

  @Test
  void noAmountMeansZeroAndNoDeadlineMeansTheDefault() {
    SelectionView view =
        service.select(new SelectCommand(null, null, null, null, null, null, null, null));

    assertThat(view.ranked())
        .extracting(SelectionView.RankedView::netMinor)
        .containsExactly(0L, -200L);
  }

  @Test
  void whenNobodyAnswersTheBaselineIsTheFallback() {
    try (StubCatalog silent =
        new StubCatalog(new FakeProvider("mute").script(Operation.REQUEST_QUOTE, Mode.TIMEOUT))) {
      SelectionService fallback = new SelectionService(silent, selector, null);

      SelectionView view =
          fallback.select(
              new SelectCommand(
                  BigDecimal.TEN, null, null, null, null, null, 100L, "baseline-route"));

      assertThat(view.outcome()).isEqualTo("fallback");
      assertThat(view.baseline()).isEqualTo("baseline-route");
      assertThat(view.provider()).isNull();
    }
  }

  @Test
  void whenNobodyAnswersAndThereIsNoBaselineThereIsNone() {
    try (StubCatalog silent =
        new StubCatalog(new FakeProvider("mute").script(Operation.REQUEST_QUOTE, Mode.TIMEOUT))) {
      SelectionService none = new SelectionService(silent, selector, null);

      assertThat(
              none.select(
                      new SelectCommand(BigDecimal.TEN, null, null, null, null, null, 100L, null))
                  .outcome())
          .isEqualTo("none");
    }
  }

  @Test
  void anInvalidInputExceptionJoinsItsMessages() {
    InvalidInputException exception =
        new InvalidInputException(java.util.List.of("a: bad", "b: worse"));

    assertThat(exception.getMessage()).isEqualTo("a: bad; b: worse");
    assertThat(exception.errors()).containsExactly("a: bad", "b: worse");
  }

  @Test
  void theMerchantAndCustomerCascadeDecidesThePolicyWhenNoneIsGivenExplicitly() {
    PolicyResolver resolver =
        new PolicyResolver(
            ((net.robbrown.actortx.validation.Validated.Valid<
                        net.robbrown.actortx.policy.PolicySet>)
                    net.robbrown.actortx.policy.PolicySet.fromMap(
                        Map.of(
                            "global", Map.of("weights", Map.of("cost", 1)),
                            "merchants",
                                Map.of(
                                    "acme",
                                    Map.of(
                                        "overrides_allowed",
                                        true,
                                        "policy",
                                        Map.of("max_bps_from_best", 50))))))
                .value());
    SelectionService cascading = new SelectionService(catalog, selector, resolver);

    SelectionView view =
        cascading.select(
            new SelectCommand(BigDecimal.TEN, null, null, "acme", null, null, 400L, null));

    assertThat(view.policy()).isEqualTo("global + merchant:acme");
    assertThat(view.excluded())
        .singleElement()
        .extracting(SelectionView.ExcludedView::provider)
        .isEqualTo("best");
  }

  @Test
  void anExplicitPolicyReplacesTheCascadeAndSaysSo() {
    SelectionView view =
        service.select(command(BigDecimal.TEN, Map.of("weights", Map.of("cost", 1))));

    assertThat(view.policy()).isEqualTo("global");
  }
}

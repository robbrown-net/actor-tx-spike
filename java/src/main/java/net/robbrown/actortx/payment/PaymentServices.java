package net.robbrown.actortx.payment;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.robbrown.actortx.events.EventSink;
import net.robbrown.actortx.selection.QuoteSelector;

/**
 * Everything a transaction needs from the system around it.
 *
 * @param selector quote-and-select
 * @param events the narrative
 * @param barrier the write-ahead barrier: empty when everything announced so far is durable,
 *     otherwise why it is not (no money moves then)
 * @param clock wall-clock milliseconds
 * @param deadlines regional cut-offs
 * @param guarantees standing guarantees
 * @param workflows the steps a payment takes, per route
 * @param ended told when a transaction reaches an end state (frees its admission slot)
 */
public record PaymentServices(
    QuoteSelector selector,
    EventSink events,
    Supplier<Optional<String>> barrier,
    LongSupplier clock,
    Deadlines deadlines,
    Guarantees guarantees,
    Workflows workflows,
    Consumer<String> ended) {

  /** Services with no barrier, deadlines, guarantees or admission, on the system clock. */
  public static PaymentServices of(QuoteSelector selector, EventSink events) {
    return new PaymentServices(
        selector,
        events,
        Optional::empty,
        System::currentTimeMillis,
        Deadlines.NONE,
        Guarantees.NONE,
        Workflows.STANDARD,
        id -> {});
  }

  /** The same services with a write-ahead barrier. */
  public PaymentServices withBarrier(Supplier<Optional<String>> newBarrier) {
    return new PaymentServices(
        selector, events, newBarrier, clock, deadlines, guarantees, workflows, ended);
  }

  /** The same services with cut-offs. */
  public PaymentServices withDeadlines(Deadlines newDeadlines) {
    return new PaymentServices(
        selector, events, barrier, clock, newDeadlines, guarantees, workflows, ended);
  }

  /** The same services with guarantees. */
  public PaymentServices withGuarantees(Guarantees newGuarantees) {
    return new PaymentServices(
        selector, events, barrier, clock, deadlines, newGuarantees, workflows, ended);
  }

  /** The same services with a workflow per route (extra steps, other orders, async settlement). */
  public PaymentServices withWorkflows(Workflows newWorkflows) {
    return new PaymentServices(
        selector, events, barrier, clock, deadlines, guarantees, newWorkflows, ended);
  }

  /** The same services with an end-of-transaction hook. */
  public PaymentServices withEnded(Consumer<String> hook) {
    return new PaymentServices(
        selector, events, barrier, clock, deadlines, guarantees, workflows, hook);
  }
}

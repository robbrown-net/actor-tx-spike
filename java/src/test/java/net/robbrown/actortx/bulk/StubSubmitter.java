package net.robbrown.actortx.bulk;

import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import net.robbrown.actortx.payment.Outcome;
import net.robbrown.actortx.service.PaymentCommand;

/**
 * A scripted submitter. Each call first returns the next scripted answer, if there is one; after
 * that every command is accepted, and its ending is completed by the test (or at once, if the stub
 * was made to complete immediately).
 */
final class StubSubmitter implements Submitter {

  private final Deque<Submission> scripted = new ConcurrentLinkedDeque<>();
  private final Map<String, CompletableFuture<Ending>> endings = new ConcurrentHashMap<>();
  private final List<PaymentCommand> commands = new CopyOnWriteArrayList<>();
  private volatile Ending immediately;

  /** Answer these, in order, before accepting. */
  StubSubmitter script(Submission... answers) {
    scripted.addAll(List.of(answers));
    return this;
  }

  /** Accept everything and end it at once with this outcome. */
  StubSubmitter completingWith(Ending outcome) {
    this.immediately = outcome;
    return this;
  }

  @Override
  public Submission submit(PaymentCommand command) {
    commands.add(command);
    if (!scripted.isEmpty()) {
      return scripted.poll();
    }
    String id = "tx-" + commands.size();
    CompletableFuture<Ending> ending = new CompletableFuture<>();
    if (immediately != null) {
      ending.complete(immediately);
    }
    endings.put(id, ending);
    return new Submission.Accepted(id, ending);
  }

  /** End one accepted payment. */
  void end(String id, Ending ending) {
    endings.get(id).complete(ending);
  }

  /** End every payment still open with this outcome. */
  void endAll(Outcome status) {
    endings.values().forEach(ending -> ending.complete(new Ending(status, "failed for a reason")));
  }

  /** Every command submitted, retries included. */
  List<PaymentCommand> commands() {
    return List.copyOf(commands);
  }

  /** The ids of accepted payments. */
  Set<String> ids() {
    return Set.copyOf(endings.keySet());
  }
}

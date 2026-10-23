package net.robbrown.actortx.review;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.robbrown.actortx.provider.Answer;
import net.robbrown.actortx.provider.Answer.NotFound;
import net.robbrown.actortx.provider.Answer.Ok;
import net.robbrown.actortx.provider.Operation;
import net.robbrown.actortx.provider.Receipt;

/**
 * A provider stub that behaves like a provider where it matters to the review: an effect happens
 * once per key (repeating it changes nothing), and a status lookup finds what happened.
 */
final class StubProviders implements ReviewProviders {

  private final Set<String> up;
  private final Set<String> effects = new HashSet<>();
  private final List<String> invoked = new ArrayList<>();
  private int lookups;
  private Answer<Receipt> refusal;

  StubProviders(String... available) {
    up = new HashSet<>(List.of(available));
  }

  /** A step that already happened at a provider, under its key. */
  StubProviders held(Operation op, String key) {
    effects.add(id(op, key));
    return this;
  }

  /** Every later invocation answers this instead of acting. */
  StubProviders refuseWith(Answer<Receipt> answer) {
    refusal = answer;
    return this;
  }

  @Override
  public boolean available(String provider) {
    return up.contains(provider);
  }

  @Override
  public synchronized Answer<Receipt> status(String provider, Operation op, String key) {
    lookups++;
    return effects.contains(id(op, key)) ? new Ok<>(receipt(provider, op, key)) : new NotFound<>();
  }

  @Override
  public synchronized Answer<Receipt> invoke(String provider, Operation op, String key) {
    invoked.add(id(op, key));
    if (refusal != null) {
      return refusal;
    }
    effects.add(id(op, key));
    return new Ok<>(receipt(provider, op, key));
  }

  /** How many effects of this operation have happened (once per key). */
  synchronized int effects(Operation op) {
    return (int) effects.stream().filter(e -> e.startsWith(op.wire() + "|")).count();
  }

  /** How many effects have happened, of any operation. */
  synchronized int totalEffects() {
    return effects.size();
  }

  /** The operations sent to providers by invoke, in order. */
  synchronized List<String> invoked() {
    return List.copyOf(invoked);
  }

  /** How many status lookups were made. */
  synchronized int lookups() {
    return lookups;
  }

  private static String id(Operation op, String key) {
    return op.wire() + "|" + key;
  }

  private static Receipt receipt(String provider, Operation op, String key) {
    return new Receipt(provider, op, key, null, null);
  }
}

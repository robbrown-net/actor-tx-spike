package net.robbrown.actortx.policy;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.robbrown.actortx.validation.Parsers;
import net.robbrown.actortx.validation.Validated;

/**
 * Cascading routing objectives: global, then merchant, then customer. The most specific layer wins
 * and everything else is inherited.
 *
 * <ul>
 *   <li>A merchant layer is honoured only when its {@code overrides_allowed} is true (a paid
 *       privilege). Otherwise it is ignored, and the resolution says so.
 *   <li>Overrides are partial: unspecified fields are inherited. {@code weights} merge key by key
 *       and are renormalised by {@link Policy}.
 *   <li>Every layer is validated up front, by building the policy it produces.
 * </ul>
 */
public final class PolicySet {

  private static final String MERCHANT_ID = "merchantId";
  private static final String CUSTOMER_ID = "customerId";
  private static final String NOT_ENTITLED = "not_entitled";

  private final Map<String, Object> global;
  private final Map<String, Object> merchants;
  private final Map<String, Object> customers;

  private PolicySet(
      Map<String, Object> global, Map<String, Object> merchants, Map<String, Object> customers) {
    this.global = global;
    this.merchants = merchants;
    this.customers = customers;
  }

  /** One layer that contributed to a resolved policy, or was ignored. */
  public sealed interface Layer {

    /** How the layer reads in {@link PolicySet#describe}, e.g. {@code "merchant:acme"}. */
    String describe();

    /** The base policy every request starts from. */
    record Global() implements Layer {
      @Override
      public String describe() {
        return "global";
      }
    }

    /** A merchant's override. */
    record Merchant(String id) implements Layer {
      @Override
      public String describe() {
        return "merchant:" + id;
      }
    }

    /** A customer's override. */
    record Customer(String id) implements Layer {
      @Override
      public String describe() {
        return "customer:" + id;
      }
    }

    /** A layer that was present but not applied, and why. */
    record Ignored(Layer layer, String reason) implements Layer {
      @Override
      public String describe() {
        return layer.describe() + " (ignored: " + reason + ")";
      }
    }
  }

  /** The policy for a request, and the layers that built it in application order. */
  public record Resolved(Policy policy, List<Layer> layers) {}

  /** The merged data so far, and the layers that contributed to it. */
  private record Acc(Map<String, Object> data, List<Layer> layers) {

    Acc with(Map<String, Object> override, Layer layer) {
      return new Acc(merge(data, override), append(layer));
    }

    Acc ignore(Layer layer) {
      return new Acc(data, append(new Layer.Ignored(layer, NOT_ENTITLED)));
    }

    private List<Layer> append(Layer layer) {
      return Stream.concat(layers.stream(), Stream.of(layer)).toList();
    }
  }

  /**
   * Validate a set of layers from plain data (decoded JSON). Sections that are not maps are empty.
   * Every problem is reported, prefixed with where it is: {@code global:}, {@code merchant:<id>:}
   * or {@code customer:<id>:}.
   */
  public static Validated<PolicySet> fromMap(Map<String, Object> data) {
    Map<String, Object> global = Parsers.stringKeys(data.get("global"));
    Map<String, Object> merchants = Parsers.stringKeys(data.get("merchants"));
    Map<String, Object> customers = Parsers.stringKeys(data.get("customers"));
    Function<Object, Map<String, Object>> overGlobal = entry -> merge(global, partial(entry));
    List<String> errors =
        Stream.of(
                policyErrors("global", global),
                layerErrors("merchant", merchants, overGlobal),
                layerErrors("customer", customers, overGlobal))
            .flatMap(List::stream)
            .toList();
    return Validated.of(new PolicySet(global, merchants, customers), errors);
  }

  /**
   * The policy for a request, from its {@code merchantId} and {@code customerId}, with the layers
   * that contributed. Layers that were present but not entitled are reported as ignored.
   *
   * @throws IllegalStateException if the applied layers together make an invalid policy, even
   *     though each layer on its own was valid
   */
  public Resolved resolve(Map<String, Object> request) {
    Acc acc = new Acc(global, List.of(new Layer.Global()));
    acc =
        withLayer(
            acc, merchants, request.get(MERCHANT_ID), Layer.Merchant::new, PolicySet::entitled);
    acc = withLayer(acc, customers, request.get(CUSTOMER_ID), Layer.Customer::new, entry -> true);
    return new Resolved(effectivePolicy(acc.data()), acc.layers());
  }

  /** A human-readable layer list, e.g. {@code "global + merchant:acme + customer:vip-1"}. */
  public static String describe(List<Layer> layers) {
    return layers.stream().map(Layer::describe).collect(Collectors.joining(" + "));
  }

  private static Acc withLayer(
      Acc acc,
      Map<String, Object> entries,
      Object rawId,
      Function<String, Layer> kind,
      Predicate<Object> entitled) {
    Object entry = rawId == null ? null : entries.get(String.valueOf(rawId));
    return entry == null
        ? acc
        : contribute(acc, entry, kind.apply(String.valueOf(rawId)), entitled);
  }

  private static Acc contribute(Acc acc, Object entry, Layer layer, Predicate<Object> entitled) {
    return entitled.test(entry) ? acc.with(partial(entry), layer) : acc.ignore(layer);
  }

  private static boolean entitled(Object entry) {
    return Boolean.TRUE.equals(Parsers.stringKeys(entry).get("overrides_allowed"));
  }

  private static Policy effectivePolicy(Map<String, Object> data) {
    if (Policy.fromMap(data) instanceof Validated.Valid<Policy>(Policy policy)) {
      return policy;
    }
    throw new IllegalStateException("the applied layers together are not a valid policy");
  }

  private static Map<String, Object> partial(Object entry) {
    return Parsers.stringKeys(Parsers.stringKeys(entry).get("policy"));
  }

  private static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> override) {
    Map<String, Object> weights = Parsers.stringKeys(base.get("weights"));
    weights.putAll(Parsers.stringKeys(override.get("weights")));
    Map<String, Object> rest = new TreeMap<>(override);
    rest.remove("weights");
    Map<String, Object> merged = new TreeMap<>(base);
    merged.putAll(rest);
    if (!weights.isEmpty()) {
      merged.put("weights", weights);
    }
    return merged;
  }

  private static List<String> layerErrors(
      String kind, Map<String, Object> entries, Function<Object, Map<String, Object>> build) {
    return entries.entrySet().stream()
        .flatMap(e -> policyErrors(kind + ":" + e.getKey(), build.apply(e.getValue())).stream())
        .toList();
  }

  private static List<String> policyErrors(String label, Map<String, Object> data) {
    if (Policy.fromMap(data) instanceof Validated.Invalid<Policy>(List<String> errors)) {
      return errors.stream().map(e -> label + ": " + e).toList();
    }
    return List.of();
  }
}

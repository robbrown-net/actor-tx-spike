package net.robbrown.actortx.tooling;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.robbrown.actortx.provider.FakeProvider;
import net.robbrown.actortx.provider.Mode;
import net.robbrown.actortx.provider.Operation;
import net.robbrown.actortx.provider.ProviderServer;
import net.robbrown.actortx.service.InvalidInputException;

/** What an operator can do to a demo provider: slow it, break it, let it drift, heal it. */
final class ProviderPresets {

  /** A provider as built: what "heal" returns it to. */
  record Base(long latencyMs, String rate, String fee, String version) {}

  /** One preset. */
  @FunctionalInterface
  interface Preset {
    void apply(Target target);
  }

  /** What a preset acts on. */
  record Target(
      String id,
      FakeProvider fake,
      ProviderServer server,
      Base base,
      double pace,
      Map<String, Object> params) {}

  private static final AtomicLong DRIFTS = new AtomicLong();

  private ProviderPresets() {}

  /** Every preset by name. */
  static Map<String, Preset> all() {
    Map<String, Preset> presets = new HashMap<>();
    speeds(presets);
    quoteFaults(presets);
    stepFaults(presets);
    housekeeping(presets);
    return presets;
  }

  private static void speeds(Map<String, Preset> presets) {
    presets.put("slow", t -> t.fake().latencyMs(Math.round(t.base().latencyMs() * 4 * t.pace())));
    presets.put("stall", t -> t.fake().latencyMs(Math.round(t.base().latencyMs() * 12 * t.pace())));
  }

  private static void quoteFaults(Map<String, Preset> presets) {
    presets.put("error", t -> t.fake().script(Operation.REQUEST_QUOTE, Mode.error("unavailable")));
    presets.put("reject", t -> t.fake().script(Operation.REQUEST_QUOTE, Mode.REJECT));
  }

  private static void stepFaults(Map<String, Preset> presets) {
    presets.put("authorise_fails", t -> t.fake().script(Operation.AUTHORISE, Mode.REJECT));
    presets.put("pay_answer_lost", t -> t.fake().script(Operation.PAY, Mode.IN_DOUBT, Mode.OK));
    presets.put("pay_vanishes", t -> t.fake().script(Operation.PAY, Mode.TIMEOUT));
    presets.put("settle_fails", t -> t.fake().script(Operation.SETTLE, Mode.REJECT));
    presets.put("settle_vanishes", t -> t.fake().script(Operation.SETTLE, Mode.TIMEOUT));
  }

  private static void housekeeping(Map<String, Preset> presets) {
    presets.put("drift", t -> t.fake().version("v" + DRIFTS.incrementAndGet() + "-" + t.id()));
    presets.put("release", t -> t.server().release());
    presets.put("reprice", ProviderPresets::reprice);
    presets.put("heal", ProviderPresets::heal);
  }

  private static void reprice(Target t) {
    if (!(t.params().get("rate") instanceof Number rate)) {
      throw new InvalidInputException(List.of("reprice needs a numeric rate"));
    }
    t.fake().rate(rate.toString());
  }

  private static void heal(Target t) {
    t.fake()
        .resetScripts()
        .latencyMs(Math.round(t.base().latencyMs() * t.pace()))
        .rate(t.base().rate())
        .fee(t.base().fee())
        .version(t.base().version());
  }
}

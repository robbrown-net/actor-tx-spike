defmodule ActorTxSpike.SelectionIntegrationTest do
  use ExUnit.Case, async: true

  alias ActorTxSpike.{Policy, Selector}
  alias ActorTxSpike.Provider.{Fake, Server}

  @request %{id: "fx-1", amount: 1_000}

  defp provider(id, fake_opts, health) do
    {:ok, fake} = Fake.start_link([id: id] ++ fake_opts)
    {:ok, server} = Server.start_link(adapter: Fake, config: fake, health: health)
    %{id: id, fake: fake, server: server}
  end

  defp wait_until(fun, tries \\ 100) do
    cond do
      fun.() -> :ok
      tries == 0 -> flunk("condition not met")
      true -> Process.sleep(5) && wait_until(fun, tries - 1)
    end
  end

  defp market do
    health = [failure_threshold: 2, cool_down_ms: 10_000, slow_ms: 80]

    %{
      cheap: provider(:cheap, [rate: 1.052, latency_ms: 25], health),
      fast: provider(:fast, [rate: 1.048], health),
      pricey: provider(:pricey, [rate: 1.040, fee: 5.0], health),
      flaky: provider(:flaky, [rate: 1.060, latency_ms: 500], health),
      drifter: provider(:drifter, [rate: 1.070], health)
    }
  end

  test "a world of mixed providers: selection adapts as providers decay and drift" do
    m = market()
    providers = Map.values(m)
    {:ok, cost_led} = Policy.new(%{"weights" => %{"cost" => 0.95, "speed" => 0.05}})
    {:ok, speed_led} = Policy.new(%{"weights" => %{"cost" => 0.2, "speed" => 0.8}})

    # Round 1: flaky misses the deadline (and best-priced rate is wasted).
    sel = Selector.select(@request, providers, cost_led, deadline_ms: 120)
    assert {:selected, %{provider_id: :drifter}} = sel.outcome
    assert {:flaky, :timeout} in sel.excluded

    # Merchant that values speed gets a different route from the same market.
    sel = Selector.select(@request, providers, speed_led, deadline_ms: 120)
    assert {:selected, %{provider_id: :drifter}} = sel.outcome

    # drifter silently changes version; the sentinel notices; it is quarantined.
    {:ok, _} = Server.probe(m.drifter.server)
    Fake.configure(m.drifter.fake, version: "v2")
    {:ok, _} = Server.probe(m.drifter.server)
    assert Server.state(m.drifter.server) == :quarantined

    sel = Selector.select(@request, providers, cost_led, deadline_ms: 120)
    assert {:selected, %{provider_id: :cheap}} = sel.outcome
    assert {:drifter, {:state, :quarantined}} in sel.excluded

    sel = Selector.select(@request, providers, speed_led, deadline_ms: 120)
    assert {:selected, %{provider_id: :fast}} = sel.outcome

    # flaky keeps timing out; the breaker opens and it stops being called at all.
    wait_until(fn -> Server.state(m.flaky.server) in [:degraded, :stalled] end)
    _ = Selector.select(@request, providers, cost_led, deadline_ms: 120)
    wait_until(fn -> Server.state(m.flaky.server) == :stalled end)

    sel = Selector.select(@request, providers, cost_led, deadline_ms: 120)
    assert {:flaky, {:state, :stalled}} in sel.excluded
    assert sel.elapsed_ms < 120
  end

  test "when every provider is unusable the baseline route is returned" do
    m = market()
    Enum.each(Map.values(m), &Fake.configure(&1.fake, quote_mode: :timeout))

    sel =
      Selector.select(@request, Map.values(m), %Policy{},
        deadline_ms: 50,
        baseline: {:route, :cheap}
      )

    assert sel.outcome == {:fallback, {:route, :cheap}}
    assert sel.ranked == []
    assert length(sel.excluded) == 5
  end
end

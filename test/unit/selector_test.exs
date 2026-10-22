defmodule ActorTxSpike.SelectorTest do
  use ExUnit.Case, async: true

  alias ActorTxSpike.Wait

  alias ActorTxSpike.{Events, Policy, Selection, Selector}
  alias ActorTxSpike.Provider.{Fake, Health, Server}

  defmodule StubServer do
    @moduledoc "Answers :health but misbehaves on :checkout."
    use GenServer
    def start_link(mode), do: GenServer.start_link(__MODULE__, mode)
    def init(mode), do: {:ok, mode}
    def handle_call(:health, _from, mode), do: {:reply, Health.new(), mode}
    def handle_call({:checkout, _op}, _from, :hang), do: {:noreply, :hang}
    def handle_call({:checkout, _op}, _from, :crash), do: {:stop, :crashed, :crashed}
  end

  @request %{id: "r1", amount: 10_000}

  defp provider(id, fake_opts \\ [], health \\ []) do
    {:ok, fake} = Fake.start_link([id: id] ++ fake_opts)
    {:ok, server} = Server.start_link(adapter: Fake, config: fake, health: health)
    %{id: id, fake: fake, server: server}
  end

  defp policy(data) do
    {:ok, p} = Policy.new(data)
    p
  end

  defp wait_until(fun, tries \\ 100), do: Wait.until!(fun, tries: tries, every_ms: 5)

  defp ids(%Selection{ranked: ranked}), do: Enum.map(ranked, & &1.provider_id)

  test "cost-only policy selects the highest net value; default opts work" do
    ps = [provider(:cheap, rate: 1.00), provider(:best, rate: 1.10), provider(:mid, rate: 1.05)]
    sel = Selector.select(@request, ps, policy(%{"weights" => %{"cost" => 1}}))

    assert {:selected, %{provider_id: :best, net: net}} = sel.outcome
    assert net == 11_000
    assert ids(sel) == [:best, :mid, :cheap]
    assert sel.excluded == []
  end

  test "fees are part of cost" do
    ps = [provider(:high_rate_high_fee, rate: 1.10, fee: 15), provider(:low_rate, rate: 1.05)]
    sel = Selector.select(@request, ps, policy(%{"weights" => %{"cost" => 1}}), deadline_ms: 500)
    assert {:selected, %{provider_id: :low_rate}} = sel.outcome
  end

  test "weights express the cost/speed trade-off" do
    slow_cheap = provider(:slow_cheap, rate: 1.00, latency_ms: 40)
    fast_dear = provider(:fast_dear, rate: 0.99)
    ps = [slow_cheap, fast_dear]

    cost_led = policy(%{"weights" => %{"cost" => 0.7, "speed" => 0.3}})
    speed_led = policy(%{"weights" => %{"cost" => 0.3, "speed" => 0.7}})

    assert {:selected, %{provider_id: :slow_cheap}} =
             Selector.select(@request, ps, cost_led, deadline_ms: 500).outcome

    assert {:selected, %{provider_id: :fast_dear}} =
             Selector.select(@request, ps, speed_led, deadline_ms: 500).outcome
  end

  test "reliability prefers a proven provider over an unknown one" do
    proven = provider(:proven)
    fresh = provider(:fresh)
    {:ok, _} = Server.probe(proven.server)

    sel =
      Selector.select(@request, [fresh, proven], policy(%{"weights" => %{"reliability" => 1}}))

    assert ids(sel) == [:proven, :fresh]
  end

  @tag :regression

  test "max_bps_from_best stops speed from buying an unacceptably bad price" do
    best = provider(:best_price, rate: 1.10, latency_ms: 30)
    fast = provider(:fast_poor, rate: 1.05)
    request = %{id: "r", amount: 100}

    loose = policy(%{"weights" => %{"cost" => 0.1, "speed" => 0.9}})
    strict = policy(%{"weights" => %{"cost" => 0.1, "speed" => 0.9}, "max_bps_from_best" => 100})

    assert {:selected, %{provider_id: :fast_poor}} =
             Selector.select(request, [best, fast], loose, deadline_ms: 500).outcome

    sel = Selector.select(request, [best, fast], strict, deadline_ms: 500)
    assert {:selected, %{provider_id: :best_price}} = sel.outcome
    assert {:fast_poor, {:worse_than_best_bps, bps}} = List.keyfind(sel.excluded, :fast_poor, 0)
    assert_in_delta bps, 454.5, 0.1
  end

  test "ties are broken deterministically by provider id" do
    ps = [provider(:b), provider(:a), provider(:c)]
    sel = Selector.select(@request, ps, policy(%{"weights" => %{"cost" => 1}}))
    assert ids(sel) == [:a, :b, :c]
  end

  test "hard constraints exclude candidates with a reason" do
    ps = [
      provider(:ok),
      provider(:short_lived, quote_ttl_ms: 50),
      provider(:slow, latency_ms: 40)
    ]

    pol = policy(%{"min_validity_ms" => 10_000, "max_latency_ms" => 20})
    # short_lived/slow both violate; 'ok' has 60s validity and ~0ms latency
    sel = Selector.select(@request, ps, pol, deadline_ms: 500)

    assert ids(sel) == [:ok]
    assert {:short_lived, :quote_expires_too_soon} in sel.excluded
    assert {:slow, :too_slow} in sel.excluded
  end

  test "stalled and quarantined providers are skipped without being called" do
    stalled = provider(:stalled, [quote_mode: {:error, :down}], failure_threshold: 1)
    assert {:error, :down} = Server.request_quote(stalled.server, %{id: "x"})
    wait_until(fn -> Server.state(stalled.server) == :stalled end)

    drifted = provider(:drifted)
    {:ok, _} = Server.probe(drifted.server)
    Fake.configure(drifted.fake, version: "2")
    {:ok, _} = Server.probe(drifted.server)

    good = provider(:good)
    sel = Selector.select(@request, [stalled, drifted, good], %Policy{})

    assert ids(sel) == [:good]
    assert {:stalled, {:state, :stalled}} in sel.excluded
    assert {:drifted, {:state, :quarantined}} in sel.excluded
  end

  @tag :regression

  test "the narrative says who was not called, and who won ahead of whom" do
    Events.subscribe()
    stalled = provider(:parked, [quote_mode: {:error, :down}], failure_threshold: 1)
    assert {:error, :down} = Server.request_quote(stalled.server, %{id: "x"})
    wait_until(fn -> Server.state(stalled.server) == :stalled end)

    request = %{id: "narr-1", amount: 100}

    Selector.select(
      request,
      [stalled, provider(:a, rate: 1.1), provider(:b, rate: 1.0)],
      policy(%{"weights" => %{"cost" => 1}})
    )

    messages = for {:tx_event, %{tx_id: "narr-1", message: m}} <- drain(), do: m
    assert "not calling parked: it is stalled" in messages
    assert Enum.any?(messages, &(&1 =~ ~r/^selected a \(.*\) ahead of b \(/))

    Selector.select(
      %{request | id: "narr-2"},
      [provider(:solo)],
      policy(%{"weights" => %{"cost" => 1}})
    )

    solo = for {:tx_event, %{tx_id: "narr-2", message: m}} <- drain(), m =~ "selected solo", do: m
    assert [msg] = solo
    refute msg =~ "ahead of"
  end

  defp drain(acc \\ []) do
    receive do
      {:tx_event, _} = e -> drain([e | acc])
    after
      50 -> Enum.reverse(acc)
    end
  end

  test "a dead provider server is reported as :down" do
    dead = provider(:dead)
    GenServer.stop(dead.server)
    sel = Selector.select(@request, [dead, provider(:good)], %Policy{})
    assert ids(sel) == [:good]
    assert {:dead, {:state, :down}} in sel.excluded
  end

  test "rejections, errors and timeouts are reported; the deadline is a hard bound" do
    ps = [
      provider(:rejecting, quote_mode: :reject),
      provider(:erroring, quote_mode: {:error, :boom}),
      provider(:stalling, latency_ms: 400),
      provider(:good)
    ]

    sel = Selector.select(@request, ps, %Policy{}, deadline_ms: 60)

    assert ids(sel) == [:good]
    assert {:rejecting, {:rejected, :no_liquidity}} in sel.excluded
    assert {:erroring, {:error, :boom}} in sel.excluded
    assert {:stalling, :timeout} in sel.excluded
    assert sel.elapsed_ms < 2_000

    # health learned from the missed deadline
    stalling = Enum.find(ps, &(&1.id == :stalling))
    wait_until(fn -> match?([{:timeout, _} | _], Server.health(stalling.server).window) end)
  end

  test "no usable quote: :none, or the caller's baseline" do
    ps = [provider(:a, quote_mode: :timeout), provider(:b, quote_mode: :reject)]

    assert %Selection{outcome: :none, ranked: []} = Selector.select(@request, ps, %Policy{})

    assert %Selection{outcome: {:fallback, :default_route}} =
             Selector.select(@request, ps, %Policy{}, baseline: :default_route)

    assert %Selection{outcome: :none} = Selector.select(@request, [], %Policy{})
  end

  @tag :capture_log
  @tag :regression
  test "a hung or crashing provider server cannot hold the selection past the deadline" do
    {:ok, hung} = StubServer.start_link(:hang)
    {:ok, crashing} = StubServer.start_link(:crash)
    Process.flag(:trap_exit, true)

    ps = [%{id: :hung, server: hung}, %{id: :crashing, server: crashing}, provider(:good)]
    # generous deadline: the healthy provider must answer even when the machine is busy
    sel = Selector.select(@request, ps, %Policy{}, deadline_ms: 400)

    assert ids(sel) == [:good]
    assert {:hung, :timeout} in sel.excluded
    assert {:error, {:exit, {:crashed, _}}} = Keyword.fetch!(sel.excluded, :crashing)
    # well below the 5 s default GenServer.call timeout that a hung server would otherwise impose
    assert sel.elapsed_ms < 2_000
  end

  describe "a provider whose bulkhead is full" do
    defp busy_provider do
      {:ok, fake} = Fake.start_link(id: :busy, rate: 1.1)
      {:ok, server} = Server.start_link(adapter: Fake, config: fake, id: :busy, max_in_flight: 1)
      %{id: :busy, server: server}
    end

    # Holds the provider's only quote slot until told to stop (a caller that has checked out and not returned).
    defp occupy(%{server: server}) do
      holder =
        spawn(fn ->
          GenServer.call(server, {:checkout, :request_quote})

          receive do
            :stop -> :ok
          end
        end)

      wait_until(fn -> Server.in_flight(server) == 1 end)
      holder
    end

    test "is asked again until a slot frees, instead of being given up on" do
      provider = busy_provider()
      holder = occupy(provider)
      Process.send_after(holder, :stop, 60)

      sel =
        Selector.select(@request, [provider], policy(%{"weights" => %{"cost" => 1}}),
          deadline_ms: 500
        )

      assert {:selected, %{provider_id: :busy}} = sel.outcome
      assert sel.elapsed_ms >= 40
    end

    test "is reported as overloaded when it stays busy to the deadline" do
      provider = busy_provider()
      holder = occupy(provider)

      sel =
        Selector.select(@request, [provider], policy(%{"weights" => %{"cost" => 1}}),
          deadline_ms: 100
        )

      assert sel.outcome == :none
      assert {:busy, {:error, :overloaded}} in sel.excluded
      send(holder, :stop)
    end
  end

  describe "currency pairs" do
    @pair_request %{
      id: "fx-2",
      amount: 10_000,
      source_currency: "USD",
      destination_currency: "INR"
    }

    defp carrying(id, currencies, fake_opts \\ []),
      do: id |> provider(fake_opts) |> Map.put(:currencies, currencies)

    test "a provider that does not carry the pair is not asked, and is reported" do
      Events.subscribe()
      india = carrying(:india, ~w(USD INR), rate: 1.0)
      europe = carrying(:europe, ~w(USD EUR), rate: 2.0)

      sel =
        Selector.select(@pair_request, [india, europe], policy(%{"weights" => %{"cost" => 1}}))

      assert {:selected, %{provider_id: :india}} = sel.outcome
      assert {:europe, {:unsupported, "USD/INR"}} in sel.excluded
      assert Fake.calls(europe.fake) == %{}

      assert_receive {:tx_event, %{kind: :provider_skipped, message: message, data: data}}

      assert message == "not calling europe: it does not carry USD to INR"

      assert data.provider == :europe
    end

    test "a provider that lists no currencies, or a request that names none, is unaffected" do
      open = provider(:open, rate: 1.0)
      narrow = carrying(:narrow, ~w(USD EUR), rate: 1.0)
      policy = policy(%{"weights" => %{"cost" => 1}})

      assert length(Selector.select(@pair_request, [open], policy).ranked) == 1
      assert length(Selector.select(@request, [narrow], policy).ranked) == 1
    end

    test "when no provider carries the pair there is no quote" do
      narrow = carrying(:narrow, ~w(USD EUR))
      sel = Selector.select(@pair_request, [narrow], policy(%{"weights" => %{"cost" => 1}}))
      assert sel.outcome == :none
      assert {:narrow, {:unsupported, "USD/INR"}} in sel.excluded
    end
  end
end

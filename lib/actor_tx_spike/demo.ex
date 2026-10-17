defmodule ActorTxSpike.Demo do
  @moduledoc """
  A live "world" for watching and poking the system: five fake FX providers
  with different personalities, a scoring policy, optional automatic traffic,
  a market that drifts, and a sentinel that probes providers on an interval.

  The dashboard (`ActorTxSpike.Panel`) drives this module through
  `snapshot/1` and `control/2`, so every button in the UI is an ordinary
  function call you can also make from IEx.
  """
  use GenServer

  alias ActorTxSpike.{Cutoff, Economics, Events, Guarantee, Ledger, Money, PolicySet}
  alias ActorTxSpike.Provider.{Fake, Health, Server}

  @providers [
    northwind: [rate: 1.0820, fee: 1.5, latency_ms: 700],
    bluebird: [rate: 1.0790, fee: 0.5, latency_ms: 250],
    cobalt: [rate: 1.0870, fee: 2.5, latency_ms: 450],
    meridian: [rate: 1.0760, fee: 0.0, latency_ms: 500],
    zenith: [rate: 1.0810, fee: 1.0, latency_ms: 900]
  ]

  @modes ~w(quote_mode accept_quote_mode authorise_mode pay_mode settle_mode cancel_accept_mode
            void_authorisation_mode refund_mode status_mode probe_mode)a

  def start_link(opts \\ []), do: GenServer.start_link(__MODULE__, opts, name: __MODULE__)

  def snapshot(server \\ __MODULE__), do: GenServer.call(server, :snapshot)
  def control(server \\ __MODULE__, command), do: GenServer.call(server, {:control, command})

  @impl true
  def init(opts) do
    health = [failure_threshold: 3, cool_down_ms: 8_000, slow_ms: 1_500, recovery_successes: 3]

    providers =
      for {id, settings} <- @providers, into: %{} do
        {:ok, fake} = Fake.start_link([id: id] ++ settings)

        {:ok, server} =
          Server.start_link(
            adapter: Fake,
            config: fake,
            id: id,
            health: health,
            call_timeout: 6_000
          )

        {id, %{id: id, fake: fake, server: server, base: Fake.settings(fake)}}
      end

    global = %{
      "weights" => %{"cost" => 0.6, "speed" => 0.3, "reliability" => 0.1},
      "max_bps_from_best" => 100,
      "min_validity_ms" => 1_000
    }

    {:ok, policy_set} = PolicySet.new(demo_policy_set(global))

    {:ok, acme_guarantee} =
      Guarantee.new(%{"merchant_id" => "acme", "period" => iso_week(), "rate" => 1.078})

    state = %{
      providers: providers,
      policy_set: policy_set,
      policy_data: Map.delete(global, "min_validity_ms"),
      global: global,
      cutoffs: %{},
      guarantees: %{"acme" => acme_guarantee},
      auto: false,
      auto_interval_ms: Keyword.get(opts, :auto_interval_ms, 4_000),
      sentinel_interval_ms: Keyword.get(opts, :sentinel_interval_ms, 10_000),
      pace: 1.0,
      counter: 0,
      prefix: Keyword.get(opts, :prefix, "tx"),
      payment_opts:
        Keyword.get(opts, :payment_opts,
          deadline_ms: 2_500,
          step_timeout_ms: 2_500,
          backoff_ms: 150,
          reconcile_interval_ms: 400,
          linger_ms: 120_000
        )
    }

    if Keyword.get(opts, :timers, true) do
      Process.send_after(self(), :market, 2_000)
      Process.send_after(self(), :sentinel, state.sentinel_interval_ms)
    end

    {:ok, state}
  end

  @impl true
  def handle_call(:snapshot, _from, state), do: {:reply, build_snapshot(state), state}

  def handle_call({:control, command}, _from, state) do
    {reply, state} = do_control(command, state)
    {:reply, reply, state}
  end

  @impl true
  def handle_info(:auto_tick, %{auto: true} = state) do
    {_reply, state} = submit(state, Money.minor(Enum.random([250, 500, 1_000, 2_500, 10_000])))
    Process.send_after(self(), :auto_tick, state.auto_interval_ms)
    {:noreply, state}
  end

  def handle_info(:auto_tick, state), do: {:noreply, state}

  def handle_info(:market, state) do
    for {_id, p} <- state.providers do
      s = Fake.settings(p.fake)
      # drift by up to +/-0.0008, kept on a 4-decimal grid and never below parity
      drift = round((:rand.uniform() - 0.5) * 16) * 10_000
      Fake.configure(p.fake, rate: max(s.rate + drift, Money.rate_scale()))
    end

    Process.send_after(self(), :market, 2_000)
    {:noreply, state}
  end

  def handle_info(:sentinel, state) do
    for {id, p} <- state.providers do
      Task.start(fn ->
        Events.emit(nil, :sentinel, :probe, "sentinel probing #{id}", %{provider: id})
        Server.probe(p.server, 3_000)
      end)
    end

    Process.send_after(self(), :sentinel, state.sentinel_interval_ms)
    {:noreply, state}
  end

  # ---- controls ----------------------------------------------------------

  defp do_control(%{"action" => "submit"} = cmd, state) do
    amount = cmd |> Map.get("amount", 1_000) |> Money.minor()

    extras =
      for {key, field} <- [
            {"merchant", :merchant_id},
            {"customer", :customer_id},
            {"region", :destination_region}
          ],
          value = cmd[key],
          is_binary(value) and value != "",
          into: %{},
          do: {field, value}

    submit(state, amount, extras)
  end

  defp do_control(%{"action" => "auto", "on" => on}, state) do
    if on and not state.auto, do: Process.send_after(self(), :auto_tick, 100)
    {{:ok, %{auto: on}}, %{state | auto: on}}
  end

  defp do_control(%{"action" => "pace", "factor" => factor}, state) do
    factor = factor |> to_number() |> max(0.05) |> min(4.0)

    for {_id, p} <- state.providers do
      Fake.configure(p.fake, latency_ms: round(p.base.latency_ms * factor))
    end

    {{:ok, %{pace: factor}}, %{state | pace: factor}}
  end

  defp do_control(%{"action" => "policy"} = cmd, state) do
    data = Map.take(cmd, ["weights", "max_bps_from_best", "min_validity_ms", "max_latency_ms"])

    with {:ok, _policy} <- ActorTxSpike.Policy.new(data),
         {:ok, set} <- PolicySet.new(demo_policy_set(data)) do
      Events.emit(
        nil,
        :operator,
        :policy_changed,
        "global policy changed: #{inspect(data)}",
        data
      )

      {{:ok, %{policy: data}}, %{state | policy_set: set, policy_data: data, global: data}}
    else
      {:error, errors} -> {{:error, Enum.join(errors, "; ")}, state}
    end
  end

  defp do_control(%{"action" => "provider", "id" => id, "do" => what} = cmd, state) do
    with {:ok, p} <- find_provider(state, id),
         {:ok, changes} <- preset(what, cmd, p, state) do
      Fake.configure(p.fake, changes)

      if what == "release" do
        Server.release(p.server)
      end

      Events.emit(nil, :operator, :provider_changed, "operator: #{what} #{p.id}", %{
        provider: p.id,
        change: what
      })

      {{:ok, %{provider: p.id, change: what}}, state}
    else
      {:error, _} = error -> {error, state}
    end
  end

  defp do_control(%{"action" => "cutoff", "region" => region} = cmd, state)
       when is_binary(region) do
    seconds = cmd |> Map.get("in_seconds", 0) |> to_number()

    if seconds > 0 do
      at = System.system_time(:millisecond) + round(seconds * 1_000)

      Events.emit(nil, :operator, :cutoff_set, "operator: #{region} cut-off in #{seconds}s", %{
        region: region
      })

      {{:ok, %{region: region, in_seconds: seconds}},
       %{state | cutoffs: Map.put(state.cutoffs, region, Cutoff.fixed(region, at))}}
    else
      {{:ok, %{region: region, cleared: true}},
       %{state | cutoffs: Map.delete(state.cutoffs, region)}}
    end
  end

  defp do_control(_other, state), do: {{:error, "unknown command"}, state}

  defp submit(state, amount, extras \\ %{}) do
    n = state.counter + 1
    id = "#{state.prefix}-#{n}"
    providers = for {pid, p} <- state.providers, do: %{id: pid, server: p.server}

    result =
      ActorTxSpike.submit(Map.merge(%{id: id, amount: amount}, extras),
        providers: providers,
        policy_set: state.policy_set,
        cutoffs: state.cutoffs,
        guarantees: state.guarantees,
        opts: state.payment_opts
      )

    {result, %{state | counter: n}}
  end

  defp find_provider(state, id) do
    case Enum.find(state.providers, fn {pid, _} -> Atom.to_string(pid) == to_string(id) end) do
      {_, p} -> {:ok, p}
      nil -> {:error, "unknown provider"}
    end
  end

  defp preset("stall", _cmd, p, _state), do: {:ok, [latency_ms: p.base.latency_ms * 12]}
  defp preset("slow", _cmd, p, _state), do: {:ok, [latency_ms: p.base.latency_ms * 4]}
  defp preset("error", _cmd, _p, _state), do: {:ok, [quote_mode: {:error, :unavailable}]}
  defp preset("reject", _cmd, _p, _state), do: {:ok, [quote_mode: :reject]}
  defp preset("authorise_fails", _cmd, _p, _state), do: {:ok, [authorise_mode: :reject]}
  defp preset("pay_answer_lost", _cmd, _p, _state), do: {:ok, [pay_mode: [:in_doubt, :ok]]}
  defp preset("pay_vanishes", _cmd, _p, _state), do: {:ok, [pay_mode: :timeout]}
  defp preset("settle_fails", _cmd, _p, _state), do: {:ok, [settle_mode: :reject]}
  defp preset("settle_vanishes", _cmd, _p, _state), do: {:ok, [settle_mode: :timeout]}

  defp preset("drift", _cmd, p, _state),
    do: {:ok, [version: "v#{System.unique_integer([:positive])}-#{p.id}"]}

  defp preset("release", _cmd, _p, _state), do: {:ok, []}

  defp preset("reprice", cmd, _p, _state) do
    case Map.fetch(cmd, "rate") do
      {:ok, rate} when is_number(rate) -> {:ok, [rate: Money.rate(rate)]}
      _ -> {:error, "reprice needs a numeric rate"}
    end
  end

  defp preset("heal", _cmd, p, state) do
    # the base holds exact internal values; Fake.configure takes decimals
    base =
      p.base
      |> Map.take([:version | @modes])
      |> Map.put(:rate, Money.rate_to_float(p.base.rate))
      |> Map.put(:fee, Money.to_major(p.base.fee))

    {:ok, [latency_ms: round(p.base.latency_ms * state.pace)] ++ Map.to_list(base)}
  end

  defp preset(other, _cmd, _p, _state), do: {:error, "unknown action #{inspect(other)}"}

  defp build_snapshot(state) do
    providers =
      for {id, p} <- state.providers do
        h = Server.health(p.server)
        s = Fake.settings(p.fake)
        stats = Health.stats(h)

        %{
          id: id,
          state: Health.state(h),
          breaker: h.breaker,
          error_rate: stats.error_rate,
          p95_ms: stats.p95_ms,
          samples: stats.samples,
          in_flight: Server.in_flight(p.server),
          version: h.version,
          recovering: h.recovering,
          rate: Money.rate_to_float(s.rate),
          fee: Money.to_major(s.fee),
          latency_ms: s.latency_ms,
          faults: for(m <- @modes, s[m] != :ok, do: %{op: m, mode: inspect(s[m])})
        }
      end
      |> Enum.sort_by(& &1.id)

    %{
      providers: providers,
      policy: state.policy_data,
      merchants: [
        %{id: "acme", entitled: true, guarantee: 1.078},
        %{id: "globex", entitled: false}
      ],
      customers: [%{id: "vip-1"}],
      cutoffs:
        for(
          {region, c} <- state.cutoffs,
          do: %{
            region: region,
            remaining_ms: Cutoff.remaining_ms(c, System.system_time(:millisecond))
          }
        ),
      ledger: Ledger.all(),
      economics: Economics.summary(),
      auto: state.auto,
      pace: state.pace,
      submitted: state.counter
    }
  end

  # acme has paid for routing overrides (cost-led, with a rate guarantee);
  # globex has not (its override is ignored); vip-1 is a speed-led special customer.
  defp demo_policy_set(global) do
    %{
      "global" => global,
      "merchants" => %{
        "acme" => %{
          "overrides_allowed" => true,
          "policy" => %{"weights" => %{"cost" => 0.9, "speed" => 0.05, "reliability" => 0.05}}
        },
        "globex" => %{"overrides_allowed" => false, "policy" => %{"weights" => %{"cost" => 1.0}}}
      },
      "customers" => %{
        "vip-1" => %{
          "policy" => %{
            "weights" => %{"cost" => 0.1, "speed" => 0.8, "reliability" => 0.1},
            "max_bps_from_best" => 150
          }
        }
      }
    }
  end

  defp iso_week do
    {year, week} = :calendar.iso_week_number(Date.to_erl(Date.utc_today()))
    "#{year}-W#{String.pad_leading(Integer.to_string(week), 2, "0")}"
  end

  defp to_number(n) when is_number(n), do: n
  defp to_number(s) when is_binary(s), do: s |> Float.parse() |> elem(0)
end

defmodule ActorTxSpike.Demo.Handler do
  @moduledoc "Adapts `ActorTxSpike.Demo` to the `ActorTxSpike.Panel` handler contract."
  def snapshot, do: ActorTxSpike.Demo.snapshot()
  def control(command), do: ActorTxSpike.Demo.control(command)
  def metrics, do: ActorTxSpike.Metrics.snapshot()
end

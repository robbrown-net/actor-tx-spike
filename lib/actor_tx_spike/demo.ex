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

  alias ActorTxSpike.Events
  alias ActorTxSpike.Provider.{Fake, Health, Server}

  @providers [
    northwind: [rate: 1.0820, fee: 1.5, latency_ms: 700],
    bluebird: [rate: 1.0790, fee: 0.5, latency_ms: 250],
    cobalt: [rate: 1.0870, fee: 2.5, latency_ms: 450],
    meridian: [rate: 1.0760, fee: 0.0, latency_ms: 500],
    zenith: [rate: 1.0810, fee: 1.0, latency_ms: 900]
  ]

  @modes ~w(quote_mode accept_quote_mode authorise_mode pay_mode cancel_accept_mode
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

    {:ok, policy} =
      ActorTxSpike.Policy.new(%{
        "weights" => %{"cost" => 0.6, "speed" => 0.3, "reliability" => 0.1},
        "max_bps_from_best" => 100,
        "min_validity_ms" => 1_000
      })

    state = %{
      providers: providers,
      policy: policy,
      policy_data: %{
        "weights" => %{"cost" => 0.6, "speed" => 0.3, "reliability" => 0.1},
        "max_bps_from_best" => 100
      },
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
    {_reply, state} = submit(state, Enum.random([250, 500, 1_000, 2_500, 10_000]))
    Process.send_after(self(), :auto_tick, state.auto_interval_ms)
    {:noreply, state}
  end

  def handle_info(:auto_tick, state), do: {:noreply, state}

  def handle_info(:market, state) do
    for {_id, p} <- state.providers do
      s = Fake.settings(p.fake)
      drift = (:rand.uniform() - 0.5) * 0.0016
      Fake.configure(p.fake, rate: Float.round(max(s.rate + drift, 1.0), 4))
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
    amount = cmd |> Map.get("amount", 1_000) |> to_number()
    submit(state, amount)
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

    case ActorTxSpike.Policy.new(data) do
      {:ok, policy} ->
        Events.emit(nil, :operator, :policy_changed, "policy changed: #{inspect(data)}", data)
        {{:ok, %{policy: data}}, %{state | policy: policy, policy_data: data}}

      {:error, errors} ->
        {{:error, Enum.join(errors, "; ")}, state}
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

  defp do_control(_other, state), do: {{:error, "unknown command"}, state}

  defp submit(state, amount) do
    n = state.counter + 1
    id = "#{state.prefix}-#{n}"
    providers = for {pid, p} <- state.providers, do: %{id: pid, server: p.server}

    result =
      ActorTxSpike.submit(%{id: id, amount: amount},
        providers: providers,
        policy: state.policy,
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

  defp preset("drift", _cmd, p, _state),
    do: {:ok, [version: "v#{System.unique_integer([:positive])}-#{p.id}"]}

  defp preset("release", _cmd, _p, _state), do: {:ok, []}

  defp preset("reprice", cmd, _p, _state) do
    case Map.fetch(cmd, "rate") do
      {:ok, rate} when is_number(rate) -> {:ok, [rate: rate * 1.0]}
      _ -> {:error, "reprice needs a numeric rate"}
    end
  end

  defp preset("heal", _cmd, p, state) do
    base = Map.take(p.base, [:rate, :fee, :version | @modes])
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
          rate: s.rate,
          fee: s.fee,
          latency_ms: s.latency_ms,
          faults: for(m <- @modes, s[m] != :ok, do: %{op: m, mode: inspect(s[m])})
        }
      end
      |> Enum.sort_by(& &1.id)

    %{
      providers: providers,
      policy: state.policy_data,
      auto: state.auto,
      pace: state.pace,
      submitted: state.counter
    }
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

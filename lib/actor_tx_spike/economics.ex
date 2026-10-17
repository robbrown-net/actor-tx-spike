defmodule ActorTxSpike.Economics do
  @moduledoc """
  The financial ledger of the system itself: what each transaction cost to run
  and what it saved or earned.

  Each finished `Payment` emits a `:tx_economics` event carrying raw
  measurements (CPU reductions, memory, bytes on the wire, state changes,
  provider calls, compensations) and its price discovery. This server prices
  them with a `ActorTxSpike.Costs` rate card and keeps running totals, overall
  and per provider. It announces each result as a `:tx_costed` event so the
  narrative, the dashboard and the audit log all show it.

  Value, per completed transaction:

    * **saved vs median**: the net we got minus the median of all quotes, what a
      naive pick would typically have yielded. Negative means we paid for speed
      or reliability.
    * **saved vs worst**: the net we got minus the worst quote.
    * **regret vs best**: the best quote minus what we got. This is the price of
      non-price policy goals, kept visible rather than hidden.
    * **margin**: against a merchant's standing guarantee (negative is exposure).

  Cost, per transaction (pico-units, see `Costs`): CPU, memory, network, storage
  and provider calls. A platform line accrues with time regardless of traffic.

  The counterfactual behind "saved" is a choice (median here); the baseline is
  always reported alongside, never hidden.
  """
  use GenServer

  alias ActorTxSpike.{Costs, Events}

  @recent 200
  @zero_value %{
    saved_vs_median: 0,
    saved_vs_worst: 0,
    regret_vs_best: 0,
    margin: 0,
    provider_fees: 0
  }

  def start_link(opts) do
    GenServer.start_link(__MODULE__, opts, name: Keyword.get(opts, :name, __MODULE__))
  end

  @doc "Totals, overall and per provider."
  @spec summary(GenServer.server()) :: map()
  def summary(server \\ __MODULE__), do: GenServer.call(server, :summary)

  @doc "The most recent per-transaction records, newest last."
  @spec recent(GenServer.server(), pos_integer()) :: [map()]
  def recent(server \\ __MODULE__, n \\ 50), do: GenServer.call(server, {:recent, n})

  @impl true
  def init(opts) do
    if Keyword.get(opts, :subscribe, true), do: Events.subscribe()
    clock = Keyword.get(opts, :clock, fn -> System.system_time(:millisecond) end)

    {:ok,
     %{
       card: Keyword.get(opts, :costs, Costs.new()),
       clock: clock,
       since_ms: clock.(),
       total: blank(),
       by_provider: %{},
       recent: :queue.new()
     }}
  end

  @impl true
  def handle_call(:summary, _from, state) do
    elapsed = state.clock.() - state.since_ms
    platform = Costs.platform(state.card, elapsed)

    {:reply,
     %{
       since_ms: state.since_ms,
       overall: finish(state.total, platform),
       by_provider: Map.new(state.by_provider, fn {id, t} -> {id, finish(t, 0)} end),
       costs: Map.from_struct(state.card)
     }, state}
  end

  def handle_call({:recent, n}, _from, state) do
    {:reply, state.recent |> :queue.to_list() |> Enum.take(-n), state}
  end

  @impl true
  def handle_info({:tx_event, %Events.Event{kind: :tx_economics} = event}, state) do
    {:noreply, record(event, state)}
  end

  def handle_info({:tx_event, _other}, state), do: {:noreply, state}

  # ---- internals ---------------------------------------------------------

  defp record(%{tx_id: tx, data: d}, state) do
    cost = Costs.price(state.card, d.usage)
    value = value(d)
    entry = %{tx: tx, outcome: d.outcome, provider: d.provider, merchant: d.merchant}
    entry = entry |> Map.put(:cost, cost) |> Map.put(:value, value)

    Events.emit(tx, :economics, :tx_costed, narrate(d.outcome, value, cost), %{
      cost: cost,
      value: value
    })

    %{
      state
      | total: add(state.total, d.outcome, value, cost),
        by_provider: add_provider(state.by_provider, d, value, cost),
        recent: push(state.recent, entry)
    }
  end

  defp value(%{outcome: :completed} = d) do
    case d.discovery do
      nil ->
        %{@zero_value | margin: d.margin, provider_fees: d.provider_fee}

      %{chosen_net: net, median_net: median, worst_net: worst, best_net: best} ->
        %{
          saved_vs_median: net - median,
          saved_vs_worst: net - worst,
          regret_vs_best: best - net,
          margin: d.margin,
          provider_fees: d.provider_fee
        }
    end
  end

  defp value(_not_completed), do: @zero_value

  defp blank do
    Map.merge(@zero_value, %{
      transactions: 0,
      completed: 0,
      failed: 0,
      needs_review: 0,
      cost: %{cpu: 0, memory: 0, network: 0, storage: 0, provider_calls: 0, total: 0}
    })
  end

  defp add(totals, outcome, value, cost) do
    totals
    |> Map.update!(:transactions, &(&1 + 1))
    |> Map.update!(outcome, &(&1 + 1))
    |> Map.merge(value, fn _k, a, b -> a + b end)
    |> Map.update!(:cost, fn c -> Map.merge(c, cost, fn _k, a, b -> a + b end) end)
  end

  defp add_provider(map, %{provider: nil}, _value, _cost), do: map

  defp add_provider(map, d, value, cost) do
    Map.update(
      map,
      d.provider,
      add(blank(), d.outcome, value, cost),
      &add(&1, d.outcome, value, cost)
    )
  end

  # Net benefit = saved vs median + margin, in pico-units, less the cost of
  # running (variable cost plus platform). Provider fees are already inside the
  # quoted net, so they are reported but not subtracted a second time.
  defp finish(totals, platform) do
    benefit = (totals.saved_vs_median + totals.margin) * Costs.picos_per_minor()

    totals
    |> Map.put(:platform_picos, platform)
    |> Map.put(:benefit_picos, benefit)
    |> Map.put(:net_picos, benefit - totals.cost.total - platform)
  end

  defp narrate(:completed, value, cost) do
    "saved #{signed(value.saved_vs_median)} vs the median quote, margin #{signed(value.margin)}; " <>
      "cost #{picos(cost.total)} to run"
  end

  defp narrate(outcome, _value, cost) do
    "#{outcome}: no value earned; cost #{picos(cost.total)} to run"
  end

  defp signed(minor) do
    if(minor > 0, do: "+", else: "") <> ActorTxSpike.Money.format(minor)
  end

  defp picos(n), do: :erlang.float_to_binary(n / 1_000_000_000_000, decimals: 7)

  defp push(queue, entry) do
    queue = :queue.in(entry, queue)
    if :queue.len(queue) > @recent, do: elem(:queue.out(queue), 1), else: queue
  end
end

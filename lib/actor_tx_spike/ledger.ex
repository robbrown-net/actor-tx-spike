defmodule ActorTxSpike.Ledger do
  @moduledoc """
  Accounts for standing guarantees: per merchant and period, how much margin
  we kept and how much exposure we carried. In-memory for now (durable store is
  roadmap Phase 3). Recording is idempotent per transaction id.
  """
  use GenServer

  def start_link(opts) do
    GenServer.start_link(__MODULE__, :ok, name: Keyword.get(opts, :name, __MODULE__))
  end

  @doc "Record one settled transaction against a guarantee. Idempotent per `tx_id`."
  @spec record(GenServer.server(), map()) :: :ok
  def record(server \\ __MODULE__, entry), do: GenServer.call(server, {:record, entry})

  @doc "Totals for one merchant and period."
  @spec summary(GenServer.server(), String.t(), String.t()) :: map()
  def summary(server \\ __MODULE__, merchant_id, period),
    do: GenServer.call(server, {:summary, merchant_id, period})

  @doc "Totals for every merchant/period with at least one entry."
  @spec all(GenServer.server()) :: [map()]
  def all(server \\ __MODULE__), do: GenServer.call(server, :all)

  @impl true
  def init(:ok), do: {:ok, %{}}

  @impl true
  def handle_call({:record, entry}, _from, entries) do
    {:reply, :ok, Map.put_new(entries, entry.tx_id, entry)}
  end

  def handle_call({:summary, merchant, period}, _from, entries) do
    group = for {_id, e} <- entries, e.merchant_id == merchant and e.period == period, do: e
    {:reply, totals(merchant, period, group), entries}
  end

  def handle_call(:all, _from, entries) do
    result =
      entries
      |> Map.values()
      |> Enum.group_by(&{&1.merchant_id, &1.period})
      |> Enum.map(fn {{m, p}, group} -> totals(m, p, group) end)
      |> Enum.sort_by(&{&1.merchant_id, &1.period})

    {:reply, result, entries}
  end

  defp sum(values), do: Enum.sum(values)

  defp totals(merchant, period, group) do
    margins = Enum.map(group, & &1.margin)

    %{
      merchant_id: merchant,
      period: period,
      count: length(group),
      guaranteed_total: group |> Enum.map(& &1.guaranteed_net) |> sum(),
      achieved_total: group |> Enum.map(& &1.achieved_net) |> sum(),
      margin_total: sum(margins),
      exposure_total: margins |> Enum.filter(&(&1 < 0)) |> sum(),
      worst: if(margins == [], do: 0, else: Enum.min(margins)),
      best: if(margins == [], do: 0, else: Enum.max(margins))
    }
  end
end

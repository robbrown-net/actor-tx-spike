defmodule ActorTxSpike.Admission do
  @moduledoc """
  Admission control: the front door's back-pressure.

  At most `max_in_flight` transactions may be running at once. A submission over the
  limit is refused straight away with `{:error, {:overloaded, retry_after_ms}}`, before
  any provider is asked or anything is recorded, so a burst cannot push the system past
  what its providers can serve. The refusal is the signal for the caller to wait and try
  again (the Bulk sender does).

  A transaction counts from admission until its payment reaches an end state
  (completed, failed or needs review), or its process dies. Submitting an id that is
  already running is the same transaction and does not count twice.

  Configure with `config :actor_tx_spike, admission: [max_in_flight: 200, retry_after_ms: 100]`
  or at run time with `configure/1`. The default is no limit.
  """
  use GenServer

  @type limit :: pos_integer() | :infinity

  def start_link(opts \\ []), do: GenServer.start_link(__MODULE__, opts, name: __MODULE__)

  @doc "May this transaction start? `{:ok, :new}` counts it; `{:ok, :existing}` is already counted."
  @spec admit(String.t()) :: {:ok, :new | :existing} | {:error, {:overloaded, pos_integer()}}
  def admit(id), do: GenServer.call(__MODULE__, {:admit, id})

  @doc "The admitted transaction is running as `pid`; its death frees the slot."
  @spec started(String.t(), pid()) :: :ok
  def started(id, pid), do: GenServer.cast(__MODULE__, {:started, id, pid})

  @doc "The transaction ended (or never started): free its slot."
  @spec release(String.t()) :: :ok
  def release(id), do: GenServer.cast(__MODULE__, {:release, id})

  @doc "Change the limit (a positive number or `:infinity`)."
  @spec configure(limit()) :: :ok | {:error, String.t()}
  def configure(limit) when limit == :infinity or (is_integer(limit) and limit > 0),
    do: GenServer.call(__MODULE__, {:configure, limit})

  def configure(_other), do: {:error, "the limit must be a positive whole number, or none"}

  @doc "Zero the counters (admitted, rejected, peak) to measure from now; running transactions stay counted."
  @spec reset() :: :ok
  def reset, do: GenServer.call(__MODULE__, :reset)

  @doc "Counters for the dashboard."
  @spec stats() :: map()
  def stats, do: GenServer.call(__MODULE__, :stats)

  # ---- server --------------------------------------------------------------

  @impl true
  def init(opts) do
    {:ok,
     %{
       limit: Keyword.get(opts, :max_in_flight, :infinity),
       retry_after_ms: Keyword.get(opts, :retry_after_ms, 100),
       slots: %{},
       monitors: %{},
       admitted: 0,
       rejected: 0,
       peak: 0
     }}
  end

  @impl true
  def handle_call({:admit, id}, _from, state) do
    cond do
      Map.has_key?(state.slots, id) -> {:reply, {:ok, :existing}, state}
      full?(state) -> {:reply, refusal(state), %{state | rejected: state.rejected + 1}}
      true -> {:reply, {:ok, :new}, take(state, id)}
    end
  end

  def handle_call({:configure, limit}, _from, state), do: {:reply, :ok, %{state | limit: limit}}

  def handle_call(:reset, _from, state) do
    {:reply, :ok, %{state | admitted: 0, rejected: 0, peak: map_size(state.slots)}}
  end

  def handle_call(:stats, _from, state) do
    {:reply,
     %{
       in_flight: map_size(state.slots),
       limit: state.limit,
       admitted: state.admitted,
       rejected: state.rejected,
       peak: state.peak,
       retry_after_ms: state.retry_after_ms
     }, state}
  end

  @impl true
  def handle_cast({:started, id, pid}, state), do: {:noreply, watch(state, id, pid)}
  def handle_cast({:release, id}, state), do: {:noreply, free(state, id)}

  @impl true
  def handle_info({:DOWN, ref, :process, _pid, _reason}, state) do
    {:noreply, state |> free(Map.get(state.monitors, ref)) |> drop_monitor(ref)}
  end

  # ---- slots ---------------------------------------------------------------

  defp full?(%{limit: :infinity}), do: false
  defp full?(%{limit: limit, slots: slots}), do: map_size(slots) >= limit

  defp refusal(state), do: {:error, {:overloaded, state.retry_after_ms}}

  defp take(state, id) do
    slots = Map.put(state.slots, id, nil)
    %{state | slots: slots, admitted: state.admitted + 1, peak: max(state.peak, map_size(slots))}
  end

  # A payment that already ended before we heard it started has no slot left to watch.
  defp watch(%{slots: slots} = state, id, pid) when is_map_key(slots, id) do
    ref = Process.monitor(pid)
    %{state | slots: Map.put(slots, id, ref), monitors: Map.put(state.monitors, ref, id)}
  end

  defp watch(state, _id, _pid), do: state

  defp free(state, id) do
    case Map.pop(state.slots, id) do
      {nil, slots} -> %{state | slots: slots}
      {ref, slots} -> drop_monitor(%{state | slots: slots}, ref)
    end
  end

  defp drop_monitor(state, ref) do
    Process.demonitor(ref, [:flush])
    %{state | monitors: Map.delete(state.monitors, ref)}
  end
end

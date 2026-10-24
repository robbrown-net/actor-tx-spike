defmodule ActorTxSpike.Reaper do
  @moduledoc """
  Stops finished payment processes in batches, and keeps what they answered.

  A payment process ends when its transaction does (completed, failed or needs review),
  and then it does nothing more. It does not keep itself alive for that: it hands its
  final view to this server, which

  * keeps the view in a public ETS table, so `ActorTxSpike.await/2`, `snapshot/1`, a
    repeated submission of the same id and the review queue still get the answer after
    the process is gone; and
  * remembers the process, and every `:sweep_ms` (default 500) stops all those that
    finished since the last sweep, in one pass over the transaction supervisor.

  The table is bounded: only the most recent `:keep` (default 10,000) finished
  transactions are kept. An older id answers `nil` or `:not_found`, and submitting it
  again would run it again, so the bound is also how long a finished id still counts as
  the same transaction.

  The table is owned by this process. If the process crashes, the store is lost, and
  payments that had finished but not yet been swept stay alive until the node restarts.
  The server is kept small so that this does not happen in practice.
  """
  use GenServer

  @default_keep 10_000
  @default_sweep_ms 500

  @doc """
  Options: `:name` (also the ETS table's name; default `ActorTxSpike.Reaper`), `:keep`
  (finished transactions remembered), `:sweep_ms` (how often finished processes are
  stopped) and `:supervisor` (who owns the payment processes).
  """
  def start_link(opts) do
    GenServer.start_link(__MODULE__, opts, name: Keyword.get(opts, :name, __MODULE__))
  end

  @doc "Record a finished payment: its final view now, and its process to stop at the next sweep."
  @spec finished(String.t(), pid(), map(), atom()) :: :ok
  def finished(id, pid, view, server \\ __MODULE__) do
    :ets.insert(server, {id, view})
    GenServer.cast(server, {:finished, id, pid})
  end

  @doc "The final view of a finished transaction, or `nil` (not finished, or no longer remembered)."
  @spec fetch(String.t(), atom()) :: map() | nil
  def fetch(id, server \\ __MODULE__) do
    case :ets.lookup(server, id) do
      [{^id, view}] -> view
      [] -> nil
    end
  end

  @doc "Is this transaction finished and still remembered? Does not copy the view."
  @spec finished?(String.t(), atom()) :: boolean()
  def finished?(id, server \\ __MODULE__), do: :ets.member(server, id)

  @impl true
  def init(opts) do
    name = Keyword.get(opts, :name, __MODULE__)
    :ets.new(name, [:set, :public, :named_table, read_concurrency: true, write_concurrency: true])

    state = %{
      table: name,
      keep: Keyword.get(opts, :keep, @default_keep),
      sweep_ms: Keyword.get(opts, :sweep_ms, @default_sweep_ms),
      supervisor: Keyword.get(opts, :supervisor, ActorTxSpike.TransactionSupervisor),
      pending: [],
      order: :queue.new(),
      size: 0
    }

    schedule(state)
    {:ok, state}
  end

  @impl true
  def handle_cast({:finished, id, pid}, state) do
    state = remember(state, id)
    {:noreply, %{state | pending: [pid | state.pending]}}
  end

  @impl true
  def handle_info(:sweep, state) do
    Enum.each(state.pending, &DynamicSupervisor.terminate_child(state.supervisor, &1))
    schedule(state)
    {:noreply, %{state | pending: []}}
  end

  defp schedule(state), do: Process.send_after(self(), :sweep, state.sweep_ms)

  # Keep the ids in arrival order and forget the oldest once there are more than `keep`.
  defp remember(state, id) do
    state = %{state | order: :queue.in(id, state.order), size: state.size + 1}
    if state.size > state.keep, do: forget_oldest(state), else: state
  end

  defp forget_oldest(state) do
    {{:value, oldest}, order} = :queue.out(state.order)
    :ets.delete(state.table, oldest)
    %{state | order: order, size: state.size - 1}
  end
end

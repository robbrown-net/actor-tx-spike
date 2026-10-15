defmodule ActorTxSpike.Events do
  @moduledoc """
  The system's narrative: a structured, ordered stream of things that happen.

  Every component announces what it is doing via `emit/5`: a transaction
  moving between phases, a selector asking providers for quotes, a provider
  receiving, processing and answering a request, a circuit breaker opening.
  Events carry a human-readable `message` plus structured `data`.

  The server keeps a bounded ring buffer (so late joiners can catch up) and
  pushes `{:tx_event, %Event{}}` to every subscriber. It is deliberately
  fire-and-forget: observing the system must never slow it down or break it.
  """
  use GenServer

  defmodule Event do
    @moduledoc "One thing that happened."
    defstruct [:seq, :at_ms, :tx_id, :actor, :kind, :message, data: %{}]

    @type t :: %__MODULE__{}
  end

  @default_capacity 1_000

  def start_link(opts) do
    GenServer.start_link(__MODULE__, opts, name: Keyword.get(opts, :name, __MODULE__))
  end

  @doc "Announce something. Safe to call when the event server is not running."
  @spec emit(String.t() | nil, term(), atom(), String.t(), map()) :: :ok
  def emit(tx_id, actor, kind, message, data \\ %{}) do
    GenServer.cast(__MODULE__, {:emit, tx_id, actor, kind, message, data})
  end

  @doc "Subscribe the caller. Returns the buffered history; future events arrive as `{:tx_event, event}`."
  @spec subscribe() :: [Event.t()]
  def subscribe, do: GenServer.call(__MODULE__, {:subscribe, self()})

  @spec recent(pos_integer()) :: [Event.t()]
  def recent(n \\ 200), do: GenServer.call(__MODULE__, {:recent, n})

  @impl true
  def init(opts) do
    {:ok,
     %{
       capacity: Keyword.get(opts, :capacity, @default_capacity),
       buffer: :queue.new(),
       size: 0,
       seq: 0,
       subscribers: %{}
     }}
  end

  @impl true
  def handle_cast({:emit, tx_id, actor, kind, message, data}, state) do
    seq = state.seq + 1

    event = %Event{
      seq: seq,
      at_ms: System.system_time(:millisecond),
      tx_id: tx_id,
      actor: actor,
      kind: kind,
      message: message,
      data: data
    }

    Enum.each(state.subscribers, fn {pid, _ref} -> send(pid, {:tx_event, event}) end)
    {:noreply, push(%{state | seq: seq}, event)}
  end

  @impl true
  def handle_call({:subscribe, pid}, _from, state) do
    subscribers =
      Map.put_new_lazy(state.subscribers, pid, fn -> Process.monitor(pid) end)

    {:reply, :queue.to_list(state.buffer), %{state | subscribers: subscribers}}
  end

  def handle_call({:recent, n}, _from, state) do
    {:reply, state.buffer |> :queue.to_list() |> Enum.take(-n), state}
  end

  @impl true
  def handle_info({:DOWN, _ref, :process, pid, _reason}, state) do
    {:noreply, %{state | subscribers: Map.delete(state.subscribers, pid)}}
  end

  defp push(%{size: size, capacity: capacity} = state, event) when size >= capacity do
    {_dropped, buffer} = :queue.out(state.buffer)
    %{state | buffer: :queue.in(event, buffer)}
  end

  defp push(state, event) do
    %{state | buffer: :queue.in(event, state.buffer), size: state.size + 1}
  end
end

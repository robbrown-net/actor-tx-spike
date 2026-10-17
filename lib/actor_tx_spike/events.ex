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

  One exception, for durability. A **durable sink** (the audit log) registers
  with `register_sink/2`, and `barrier/0` returns only once every sink has
  written everything emitted before the call. `Payment` calls it before each
  money-moving step, so the log is written *ahead* of the effect: after a crash
  the log can never be missing a call that was actually sent. If a registered
  sink dies, `barrier/0` fails until it is replaced, and payments stop moving
  money rather than run unrecorded (fail closed).
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

  @doc """
  Register a durable sink under `name`. The sink must answer
  `GenServer.call(pid, :flush)` once it has handled every event sent so far.
  Registering again under the same name replaces the sink and clears its failure.
  """
  @spec register_sink(term(), pid(), GenServer.server()) :: :ok
  def register_sink(name, pid, server \\ __MODULE__),
    do: GenServer.call(server, {:register_sink, name, pid})

  @doc "Remove a sink that is stopping on purpose (a crash leaves it failed). Never blocks."
  @spec unregister_sink(term(), GenServer.server()) :: :ok
  def unregister_sink(name, server \\ __MODULE__),
    do: GenServer.cast(server, {:unregister_sink, name})

  @doc """
  Return `:ok` once every durable sink has written all events emitted before this
  call, or `{:error, reason}` if one is down or does not answer.
  """
  @spec barrier(GenServer.server()) :: :ok | {:error, term()}
  def barrier(server \\ __MODULE__) do
    # Ask for the sinks, then flush them from the caller: the event server itself
    # never waits on a sink, so a slow or stopping sink cannot wedge it. Every
    # event emitted before this call is already in each sink's mailbox by the
    # time the sinks are listed, so the flush answers after they are written.
    %{sinks: sinks, failed: failed} = GenServer.call(server, :sinks)

    case Enum.at(failed, 0) do
      nil -> flush_all(sinks, server)
      name -> {:error, {:sink_down, name}}
    end
  catch
    :exit, reason -> {:error, {:events_unavailable, reason}}
  end

  defp flush_all(sinks, server) do
    Enum.find_value(sinks, :ok, fn {name, pid} ->
      try do
        GenServer.call(pid, :flush, 5_000)
        nil
      catch
        :exit, reason ->
          if(still_registered?(name, server), do: {:error, {:sink_unresponsive, name, reason}})
      end
    end)
  end

  # A sink that stopped on purpose unregisters before it exits, so by the time
  # its flush fails the event server already knows; only a registered sink that
  # does not answer is a failure.
  defp still_registered?(name, server) do
    %{sinks: sinks} = GenServer.call(server, :sinks)
    List.keymember?(sinks, name, 0)
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
       subscribers: %{},
       sinks: %{},
       failed_sinks: MapSet.new()
     }}
  end

  @impl true
  def handle_cast({:unregister_sink, name}, state) do
    {:noreply, %{drop_sink(state, name) | failed_sinks: MapSet.delete(state.failed_sinks, name)}}
  end

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

  def handle_call({:register_sink, name, pid}, _from, state) do
    state = drop_sink(state, name)
    ref = Process.monitor(pid)

    {:reply, :ok,
     %{
       state
       | sinks: Map.put(state.sinks, name, %{pid: pid, ref: ref}),
         failed_sinks: MapSet.delete(state.failed_sinks, name)
     }}
  end

  def handle_call(:sinks, _from, state) do
    sinks = for {name, %{pid: pid}} <- state.sinks, do: {name, pid}
    {:reply, %{sinks: sinks, failed: state.failed_sinks}, state}
  end

  def handle_call({:recent, n}, _from, state) do
    {:reply, state.buffer |> :queue.to_list() |> Enum.take(-n), state}
  end

  @impl true
  def handle_info({:DOWN, ref, :process, pid, _reason}, state) do
    failed =
      for {name, %{ref: ^ref}} <- state.sinks, into: state.failed_sinks, do: name

    {:noreply, %{state | subscribers: Map.delete(state.subscribers, pid), failed_sinks: failed}}
  end

  defp drop_sink(state, name) do
    case Map.pop(state.sinks, name) do
      {nil, _} ->
        state

      {%{ref: ref}, sinks} ->
        Process.demonitor(ref, [:flush])
        %{state | sinks: sinks}
    end
  end

  defp push(%{size: size, capacity: capacity} = state, event) when size >= capacity do
    {_dropped, buffer} = :queue.out(state.buffer)
    %{state | buffer: :queue.in(event, buffer)}
  end

  defp push(state, event) do
    %{state | buffer: :queue.in(event, state.buffer), size: state.size + 1}
  end
end

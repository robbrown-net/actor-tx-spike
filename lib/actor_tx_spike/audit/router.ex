defmodule ActorTxSpike.Audit.Router do
  @moduledoc """
  Regional audit logs: data residency for the record.

  Some jurisdictions require payment or personal data to stay in-country, so one
  global log cannot hold everything. The router keeps one hash-chained
  `ActorTxSpike.Audit` log per named region (a file `<dir>/<name>.log`, each
  independently verifiable and deliverable) and sends every event of a
  transaction to the logs its jurisdiction decision names
  (`tx_received.data.eligibility.logs`, from `ActorTxSpike.Eligibility`).

  * Events without a transaction (provider health, operator actions) go to the
    `GLOBAL` log, which always exists.
  * A transaction whose logs are unknown to the router (it started before the
    router did, with no decision seen) goes to `GLOBAL`.
  * A decision that names a log the router does not have **crashes the router**:
    the record has nowhere lawful to go, so the system must stop moving money
    (the durable-sink barrier then fails closed) rather than put it elsewhere.

  The router is a single durable sink: `Events.barrier/0` waits for it, which
  waits for every regional log, so the write-ahead guarantee covers all regions.
  """
  use GenServer

  alias ActorTxSpike.{Audit, Events}

  @global "GLOBAL"

  def start_link(opts) do
    GenServer.start_link(__MODULE__, opts, name: Keyword.get(opts, :name, __MODULE__))
  end

  @doc "Paths of the regional logs, by name."
  @spec paths(GenServer.server()) :: %{String.t() => Path.t()}
  def paths(server \\ __MODULE__), do: GenServer.call(server, :paths)

  @doc "Record count and head hash of every regional log, by name."
  @spec heads(GenServer.server()) :: %{String.t() => map()}
  def heads(server \\ __MODULE__), do: GenServer.call(server, :heads)

  @impl true
  def init(opts) do
    # a regional log that dies must take the router with it (fail closed), and a
    # log that refuses to start must be reported rather than kill us unannounced
    Process.flag(:trap_exit, true)
    dir = Keyword.fetch!(opts, :dir)
    names = opts |> Keyword.get(:logs, []) |> Enum.concat([@global]) |> Enum.uniq()
    File.mkdir_p!(dir)
    sync = Keyword.get(opts, :sync, true)
    name = Keyword.get(opts, :name, __MODULE__)
    subscribe? = Keyword.get(opts, :subscribe, true)

    case start_logs(names, dir, sync) do
      {:ok, audits} ->
        if subscribe? do
          Events.subscribe()
          Events.register_sink({:audit_router, name}, self())
        end

        {:ok,
         %{
           dir: dir,
           audits: audits,
           tx_logs: %{},
           sink: if(subscribe?, do: {:audit_router, name})
         }}

      {:error, reason} ->
        {:stop, reason}
    end
  end

  @impl true
  def handle_call(:paths, _from, state) do
    paths = Map.new(state.audits, fn {log, _} -> {log, Path.join(state.dir, log <> ".log")} end)
    {:reply, paths, state}
  end

  def handle_call(:heads, _from, state),
    do: {:reply, Map.new(state.audits, fn {log, audit} -> {log, Audit.head(audit)} end), state}

  def handle_call(:flush, _from, state) do
    for {_log, audit} <- state.audits, do: :ok = GenServer.call(audit, :flush)
    {:reply, :ok, state}
  end

  @impl true
  def handle_info({:tx_event, event}, state), do: {:noreply, route(event, state)}

  def handle_info({:EXIT, pid, reason}, state) do
    case Enum.find(state.audits, fn {_log, audit} -> audit == pid end) do
      {log, _} -> {:stop, {:log_down, log, reason}, state}
      nil -> {:stop, reason, state}
    end
  end

  @impl true
  def terminate(reason, state) do
    if state.sink != nil and reason in [:normal, :shutdown],
      do: Events.unregister_sink(state.sink)

    # the regional logs belong to the router: a normal stop must not orphan them
    for {_log, audit} <- state.audits, Process.alive?(audit), do: GenServer.stop(audit)
    :ok
  end

  # ---- routing -----------------------------------------------------------

  defp route(event, state) do
    state = learn(event, state)

    for log <- targets(event, state) do
      case Map.fetch(state.audits, log) do
        {:ok, audit} ->
          :ok = Audit.record(audit, event)

        :error ->
          raise "no audit log named #{inspect(log)} for #{event.tx_id}: refusing to record it elsewhere"
      end
    end

    # the last event of a transaction closes its routing entry
    if event.kind == :tx_costed,
      do: %{state | tx_logs: Map.delete(state.tx_logs, event.tx_id)},
      else: state
  end

  defp learn(%{kind: kind, tx_id: tx, data: %{eligibility: %{logs: logs}}}, state)
       when kind in [:tx_received, :tx_resumed],
       do: %{state | tx_logs: Map.put(state.tx_logs, tx, logs)}

  defp learn(_event, state), do: state

  defp targets(%{tx_id: nil}, _state), do: [@global]
  defp targets(%{tx_id: tx}, state), do: Map.get(state.tx_logs, tx, [@global])

  defp start_logs(names, dir, sync) do
    Enum.reduce_while(names, {:ok, %{}}, fn log, {:ok, acc} ->
      path = Path.join(dir, log <> ".log")
      name = :"#{__MODULE__}.#{dir}.#{log}"

      case Audit.start_link(path: path, name: name, subscribe: false, sync: sync) do
        {:ok, pid} -> {:cont, {:ok, Map.put(acc, log, pid)}}
        {:error, reason} -> {:halt, {:error, {:log_refused, log, reason}}}
      end
    end)
  end
end

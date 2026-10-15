defmodule ActorTxSpike.Provider.Server do
  @moduledoc """
  Facade process for one provider: a circuit breaker, health model and
  bulkhead in front of an `ActorTxSpike.Provider` adapter.

  The adapter call runs in a task owned by the *caller* (never in this server),
  so one slow provider never blocks this server or other callers, and every
  call has an enforced timeout. The server only does admission
  (`checkout`), bookkeeping (`record`) and in-flight accounting, monitoring
  callers so a crashed caller cannot leak a slot.
  """
  use GenServer

  alias ActorTxSpike.Provider.Health

  def start_link(opts) do
    GenServer.start_link(__MODULE__, opts, Keyword.take(opts, [:name]))
  end

  @ops [
    :request_quote,
    :accept_quote,
    :authorise,
    :pay,
    :settle,
    :cancel_accept,
    :void_authorisation,
    :refund,
    :status
  ]

  @doc """
  Invoke an adapter operation (`#{inspect(@ops)}`) through the breaker.

  Takes an optional per-call `timeout` (ms). The server enforces it: an
  overdue adapter call is killed, returns `:timeout`, and is recorded as a
  timeout in the health model. Default: the server's `:call_timeout` (5000).
  """
  def invoke(server, op, args, timeout \\ :default) when op in @ops,
    do: request(server, op, args, timeout)

  def request_quote(server, request, timeout \\ :default),
    do: invoke(server, :request_quote, [request], timeout)

  def status(server, op, key, timeout \\ :default),
    do: invoke(server, :status, [op, key], timeout)

  @doc "Probe the provider (sentinel entry point). A changed version quarantines it."
  def probe(server, timeout \\ :default) do
    result = request(server, :probe, [], timeout)

    case result do
      {:ok, %{version: version}} -> GenServer.call(server, {:observe_version, version})
      _other -> :ok
    end

    result
  end

  def health(server), do: GenServer.call(server, :health)
  def state(server), do: Health.state(health(server))
  def in_flight(server), do: GenServer.call(server, :in_flight)
  def release(server), do: GenServer.call(server, :release)

  @impl true
  def init(opts) do
    {:ok,
     %{
       id: Keyword.get(opts, :id),
       adapter: Keyword.fetch!(opts, :adapter),
       config: Keyword.get(opts, :config),
       health: Health.new(Keyword.get(opts, :health, [])),
       max_in_flight: Keyword.get(opts, :max_in_flight, 10),
       call_timeout: Keyword.get(opts, :call_timeout, 5_000),
       in_flight: MapSet.new()
     }}
  end

  @impl true
  def handle_call(:checkout, {pid, _tag}, state) do
    with false <- MapSet.size(state.in_flight) >= state.max_in_flight,
         {:ok, health} <- Health.checkout(state.health, now()) do
      ref = Process.monitor(pid)

      {:reply, {:ok, state.adapter, state.config, ref, state.call_timeout},
       %{state | health: health, in_flight: MapSet.put(state.in_flight, ref)}}
    else
      true -> {:reply, {:error, :overloaded}, state}
      {:error, _reason} = error -> {:reply, error, state}
    end
  end

  def handle_call(:health, _from, state), do: {:reply, state.health, state}
  def handle_call(:in_flight, _from, state), do: {:reply, MapSet.size(state.in_flight), state}

  def handle_call({:observe_version, version}, _from, state) do
    before = Health.state(state.health)
    {health, verdict} = Health.observe_version(state.health, version)
    state = %{state | health: health}

    reason =
      case verdict do
        {:drift, old, new} -> "version changed #{old} -> #{new}"
        _ -> "probe observed version #{version}"
      end

    announce_health(state, before, reason)
    {:reply, verdict, state}
  end

  def handle_call(:release, _from, state) do
    before = Health.state(state.health)
    state = %{state | health: Health.release(state.health)}
    announce_health(state, before, "released from quarantine")
    {:reply, :ok, state}
  end

  @impl true
  def handle_cast({:record, ref, outcome, latency_ms}, state) do
    Process.demonitor(ref, [:flush])
    before = Health.state(state.health)
    health = Health.record(state.health, outcome, latency_ms, now())
    state = %{state | health: health, in_flight: MapSet.delete(state.in_flight, ref)}
    announce_health(state, before, "call #{outcome} in #{latency_ms}ms")
    {:noreply, state}
  end

  @impl true
  def handle_info({:DOWN, ref, :process, _pid, _reason}, state) do
    {:noreply,
     %{
       state
       | health: Health.abandon(state.health),
         in_flight: MapSet.delete(state.in_flight, ref)
     }}
  end

  defp request(server, op, args, timeout) do
    case GenServer.call(server, :checkout) do
      {:ok, adapter, config, ref, default_timeout} ->
        timeout = if timeout == :default, do: default_timeout, else: timeout
        started = now()
        result = invoke_with_timeout(adapter, op, [config | args], timeout)
        GenServer.cast(server, {:record, ref, classify(op, result), now() - started})
        result

      {:error, _reason} = error ->
        error
    end
  end

  defp invoke_with_timeout(adapter, op, args, timeout) do
    task = Task.async(fn -> call_adapter(adapter, op, args) end)

    case Task.yield(task, timeout) || Task.shutdown(task, :brutal_kill) do
      {:ok, result} -> result
      _overdue -> :timeout
    end
  end

  defp call_adapter(adapter, op, args) do
    apply(adapter, op, args)
  rescue
    exception -> {:error, {:raised, exception}}
  catch
    kind, reason -> {:error, {kind, reason}}
  end

  defp classify(_op, {:ok, _}), do: :success
  defp classify(_op, {:rejected, _}), do: :rejected
  defp classify(:status, :not_found), do: :success
  defp classify(_op, :timeout), do: :timeout
  defp classify(_op, :in_doubt), do: :timeout
  defp classify(_op, _other), do: :error

  defp announce_health(state, before, reason) do
    after_state = Health.state(state.health)
    stats = Health.stats(state.health)
    id = state.id

    message =
      if before == after_state,
        do: "#{id}: #{after_state} (#{reason})",
        else: "#{id}: #{before} -> #{after_state} (#{reason})"

    ActorTxSpike.Events.emit(nil, {:provider, id}, :health, message, %{
      provider: id,
      state: after_state,
      previous: before,
      breaker: state.health.breaker,
      error_rate: stats.error_rate,
      p95_ms: stats.p95_ms,
      samples: stats.samples,
      in_flight: MapSet.size(state.in_flight),
      version: state.health.version,
      recovering: state.health.recovering
    })
  end

  defp now, do: System.monotonic_time(:millisecond)
end

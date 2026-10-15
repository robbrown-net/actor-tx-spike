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

  @doc """
  Operations take an optional per-call `timeout` (ms). The server enforces it:
  an overdue adapter call is killed, returns `:timeout`, and is recorded as a
  timeout in the health model. Default: the server's `:call_timeout` (5000).
  """
  def request_quote(server, request, timeout \\ :default),
    do: request(server, :request_quote, [request], timeout)

  def execute(server, quote, key, timeout \\ :default),
    do: request(server, :execute, [quote, key], timeout)

  def status(server, key, timeout \\ :default), do: request(server, :status, [key], timeout)

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
    {health, verdict} = Health.observe_version(state.health, version)
    {:reply, verdict, %{state | health: health}}
  end

  def handle_call(:release, _from, state) do
    {:reply, :ok, %{state | health: Health.release(state.health)}}
  end

  @impl true
  def handle_cast({:record, ref, outcome, latency_ms}, state) do
    Process.demonitor(ref, [:flush])

    {:noreply,
     %{
       state
       | health: Health.record(state.health, outcome, latency_ms, now()),
         in_flight: MapSet.delete(state.in_flight, ref)
     }}
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
    task = Task.async(fn -> invoke(adapter, op, args) end)

    case Task.yield(task, timeout) || Task.shutdown(task, :brutal_kill) do
      {:ok, result} -> result
      _overdue -> :timeout
    end
  end

  defp invoke(adapter, op, args) do
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

  defp now, do: System.monotonic_time(:millisecond)
end

defmodule ActorTxSpike.Provider.Server do
  @moduledoc """
  Facade process for one provider: a circuit breaker, health model and
  bulkhead in front of an `ActorTxSpike.Provider` adapter.

  The adapter call runs in the *caller's* process, so one slow provider never
  blocks this server (or other callers). The server only does admission
  (`checkout`), bookkeeping (`record`) and in-flight accounting, monitoring
  callers so a crashed caller cannot leak a slot.
  """
  use GenServer

  alias ActorTxSpike.Provider.Health

  def start_link(opts) do
    GenServer.start_link(__MODULE__, opts, Keyword.take(opts, [:name]))
  end

  def request_quote(server, request), do: request(server, :request_quote, [request])
  def execute(server, quote, key), do: request(server, :execute, [quote, key])
  def status(server, key), do: request(server, :status, [key])

  @doc "Probe the provider (sentinel entry point). A changed version quarantines it."
  def probe(server) do
    result = request(server, :probe, [])

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
       in_flight: MapSet.new()
     }}
  end

  @impl true
  def handle_call(:checkout, {pid, _tag}, state) do
    with false <- MapSet.size(state.in_flight) >= state.max_in_flight,
         {:ok, health} <- Health.checkout(state.health, now()) do
      ref = Process.monitor(pid)

      {:reply, {:ok, state.adapter, state.config, ref},
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

  defp request(server, op, args) do
    case GenServer.call(server, :checkout) do
      {:ok, adapter, config, ref} ->
        started = now()
        result = invoke(adapter, op, [config | args])
        GenServer.cast(server, {:record, ref, classify(op, result), now() - started})
        result

      {:error, _reason} = error ->
        error
    end
  end

  defp invoke(adapter, op, args) do
    apply(adapter, op, args)
  rescue
    exception -> {:error, {:raised, exception}}
  catch
    :exit, reason -> {:error, {:exit, reason}}
  end

  defp classify(_op, {:ok, _}), do: :success
  defp classify(_op, {:rejected, _}), do: :rejected
  defp classify(:status, :not_found), do: :success
  defp classify(_op, :timeout), do: :timeout
  defp classify(_op, :in_doubt), do: :timeout
  defp classify(_op, _other), do: :error

  defp now, do: System.monotonic_time(:millisecond)
end

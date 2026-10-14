defmodule ActorTxSpike.Subsystem do
  @moduledoc """
  Fake subsystem worker. Idempotent per `tx_id`. Can be instructed to crash
  on the next request so supervision + transaction retries can be exercised.
  """
  use GenServer

  def start_link(opts) do
    name = Keyword.fetch!(opts, :name)
    GenServer.start_link(__MODULE__, opts, name: name)
  end

  def process(name, tx_id, payload) do
    GenServer.call(name, {:process, tx_id, payload}, 5_000)
  end

  def crash_next(name), do: GenServer.call(name, :crash_next)

  def processed?(name, tx_id), do: GenServer.call(name, {:processed?, tx_id})

  @impl true
  def init(opts) do
    {:ok,
     %{
       id: Keyword.fetch!(opts, :id),
       crash_next: false,
       seen: %{}
     }}
  end

  @impl true
  def handle_call(:crash_next, _from, state) do
    {:reply, :ok, %{state | crash_next: true}}
  end

  def handle_call({:processed?, tx_id}, _from, state) do
    {:reply, Map.has_key?(state.seen, tx_id), state}
  end

  def handle_call({:process, tx_id, payload}, _from, %{crash_next: true} = state) do
    _ = payload
    _ = tx_id
    Process.exit(self(), :kill)
    {:noreply, state}
  end

  def handle_call({:process, tx_id, payload}, _from, state) do
    case Map.fetch(state.seen, tx_id) do
      {:ok, result} ->
        {:reply, {:ok, result}, state}

      :error ->
        delay = Application.get_env(:actor_tx_spike, :subsystem_delay_ms, 0)
        if delay > 0, do: Process.sleep(delay)

        result = %{
          subsystem: state.id,
          tx_id: tx_id,
          echo: payload
        }

        {:reply, {:ok, result}, %{state | seen: Map.put(state.seen, tx_id, result)}}
    end
  end
end

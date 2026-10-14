defmodule ActorTxSpike.Store do
  @moduledoc """
  Process-local map persisted to disk so a node restart can resume in-flight work.
  """
  use GenServer

  defstruct path: nil, entries: %{}

  def start_link(opts \\ []) do
    GenServer.start_link(__MODULE__, opts, name: __MODULE__)
  end

  def put(snapshot) when is_map(snapshot) do
    GenServer.call(__MODULE__, {:put, snapshot})
  end

  def get(id), do: GenServer.call(__MODULE__, {:get, id})

  def inflight, do: GenServer.call(__MODULE__, :inflight)

  def reset do
    GenServer.call(__MODULE__, :reset)
  end

  @impl true
  def init(opts) do
    path = Keyword.get(opts, :path) || Application.fetch_env!(:actor_tx_spike, :store_path)
    File.mkdir_p!(Path.dirname(path))
    entries = load(path)
    {:ok, %__MODULE__{path: path, entries: entries}}
  end

  @impl true
  def handle_call({:put, snapshot}, _from, state) do
    entries = Map.put(state.entries, snapshot.id, snapshot)
    persist(state.path, entries)
    {:reply, :ok, %{state | entries: entries}}
  end

  def handle_call({:get, id}, _from, state) do
    {:reply, Map.get(state.entries, id), state}
  end

  def handle_call(:inflight, _from, state) do
    inflight =
      state.entries
      |> Map.values()
      |> Enum.reject(&(&1.state in [:committed, :failed]))

    {:reply, inflight, state}
  end

  def handle_call(:reset, _from, state) do
    persist(state.path, %{})
    {:reply, :ok, %{state | entries: %{}}}
  end

  defp persist(path, entries) do
    File.write!(path, :erlang.term_to_binary(entries), [:sync])
  end

  defp load(path) do
    case File.read(path) do
      {:ok, ""} ->
        %{}

      {:ok, bin} ->
        :erlang.binary_to_term(bin)

      {:error, :enoent} ->
        %{}
    end
  end
end

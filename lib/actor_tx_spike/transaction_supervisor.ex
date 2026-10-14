defmodule ActorTxSpike.TransactionSupervisor do
  @moduledoc false
  use DynamicSupervisor

  def start_link(opts \\ []) do
    DynamicSupervisor.start_link(__MODULE__, opts, name: __MODULE__)
  end

  @impl true
  def init(_opts) do
    DynamicSupervisor.init(strategy: :one_for_one)
  end

  def start_transaction(opts) do
    spec = %{
      id: {:transaction, Keyword.fetch!(opts, :id)},
      start: {ActorTxSpike.Transaction, :start_link, [opts]},
      restart: :temporary
    }

    DynamicSupervisor.start_child(__MODULE__, spec)
  end
end

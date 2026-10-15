defmodule ActorTxSpike.TransactionSupervisor do
  @moduledoc false
  use DynamicSupervisor

  def start_link(opts) do
    DynamicSupervisor.start_link(__MODULE__, opts, name: __MODULE__)
  end

  @impl true
  def init(_opts) do
    DynamicSupervisor.init(strategy: :one_for_one)
  end

  def start_payment(args) do
    spec = %{
      id: {:payment, Keyword.fetch!(args, :id)},
      start: {ActorTxSpike.Payment, :start_link, [args]},
      restart: :temporary
    }

    DynamicSupervisor.start_child(__MODULE__, spec)
  end
end

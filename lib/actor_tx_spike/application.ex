defmodule ActorTxSpike.Application do
  @moduledoc false
  use Application

  @impl true
  def start(_type, _args) do
    children = [
      ActorTxSpike.Events,
      ActorTxSpike.Metrics,
      {ActorTxSpike.Ledger, []},
      {Registry, keys: :unique, name: ActorTxSpike.Registry},
      ActorTxSpike.TransactionSupervisor
    ]

    Supervisor.start_link(children, strategy: :one_for_one, name: ActorTxSpike.Supervisor)
  end
end

defmodule ActorTxSpike.Application do
  @moduledoc false
  use Application

  @impl true
  def start(_type, _args) do
    children = [
      {Registry, keys: :unique, name: ActorTxSpike.Registry},
      ActorTxSpike.Store,
      Supervisor.child_spec({ActorTxSpike.Subsystem, name: :subsystem_a, id: :a},
        id: :subsystem_a
      ),
      Supervisor.child_spec({ActorTxSpike.Subsystem, name: :subsystem_b, id: :b},
        id: :subsystem_b
      ),
      ActorTxSpike.TransactionSupervisor
    ]

    opts = [strategy: :one_for_one, name: ActorTxSpike.Supervisor]
    {:ok, pid} = Supervisor.start_link(children, opts)
    _ = ActorTxSpike.restore_inflight()
    {:ok, pid}
  end
end

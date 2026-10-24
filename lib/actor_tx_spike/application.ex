defmodule ActorTxSpike.Application do
  @moduledoc """
  Starts the system.

  The audit log is the write-ahead log and the recovery store, so it is part of
  the supervision tree, not an add-on. Configure it with

      config :actor_tx_spike, audit: [dir: "audit", logs: ["GLOBAL", "IN", "CN"]]

  and the regional log router (`ActorTxSpike.Audit.Router`) starts right after the
  event stream and is **restarted if it dies**. While it is down the durable-sink
  barrier fails, so no money moves (fail closed); when it is back it re-registers
  and the barrier recovers. `logs` must name every log the jurisdiction rules can
  send records to.

  With `config :actor_tx_spike, require_audit: true` (set in production) the
  application **refuses to start** without an `:audit` setting: a payments system
  must not run unlogged by accident.
  """
  use Application

  alias ActorTxSpike.Audit.Router

  @impl true
  def start(_type, _args) do
    config = Application.get_all_env(:actor_tx_spike)

    with :ok <- check(config) do
      Supervisor.start_link(children(config),
        strategy: :one_for_one,
        name: ActorTxSpike.Supervisor
      )
    end
  end

  @doc "Refuse a configuration that requires an audit log but has none."
  @spec check(keyword()) :: :ok | {:error, :audit_required}
  def check(config) do
    if Keyword.get(config, :require_audit, false) and Keyword.get(config, :audit) == nil,
      do: {:error, :audit_required},
      else: :ok
  end

  @doc "The supervised children for a configuration."
  @spec children(keyword()) :: [Supervisor.child_spec() | module() | {module(), term()}]
  def children(config) do
    [ActorTxSpike.Events] ++
      audit_children(Keyword.get(config, :audit)) ++
      [
        ActorTxSpike.Metrics,
        ActorTxSpike.Economics,
        {ActorTxSpike.Ledger, []},
        {Registry, keys: :unique, name: ActorTxSpike.Registry},
        {ActorTxSpike.Admission, Keyword.get(config, :admission, [])},
        {ActorTxSpike.Reaper, []},
        ActorTxSpike.TransactionSupervisor
      ]
  end

  defp audit_children(nil), do: []
  defp audit_children(opts), do: [{Router, Keyword.take(opts, [:dir, :logs, :name, :sync])}]
end

defmodule ActorTxSpike do
  @moduledoc """
  Public API for the transaction spike.

  A transaction is an OTP `gen_statem` that walks:

      queued → awaiting_a → awaiting_b → committed
                                    ↘ compensating → failed

  Subsystems A and B are supervised workers. Calls are at-least-once;
  handlers are idempotent by `tx_id`.
  """

  alias ActorTxSpike.{Store, Transaction, TransactionSupervisor}

  @spec submit(map()) :: {:ok, String.t()} | {:error, term()}
  def submit(payload) when is_map(payload) do
    id = payload[:id] || payload["id"] || generate_id()
    payload = Map.put(payload, :id, id)

    case TransactionSupervisor.start_transaction(id: id, payload: payload) do
      {:ok, _pid} -> {:ok, id}
      {:error, {:already_started, _pid}} -> {:ok, id}
      other -> other
    end
  end

  @spec await(String.t(), timeout()) :: {:ok, map()} | {:error, term()}
  def await(id, timeout \\ 5_000) do
    Transaction.await(id, timeout)
  end

  @spec snapshot(String.t()) :: map() | nil
  def snapshot(id), do: Store.get(id)

  @spec restore_inflight() :: [{:ok, pid()} | {:error, term()}]
  def restore_inflight do
    Store.inflight()
    |> Enum.map(fn snapshot ->
      TransactionSupervisor.start_transaction(
        id: snapshot.id,
        payload: snapshot.payload,
        snapshot: snapshot
      )
    end)
  end

  defp generate_id do
    Base.encode16(:crypto.strong_rand_bytes(8), case: :lower)
  end
end

defmodule ActorTxSpike do
  @moduledoc """
  Public API of the transaction spike.

  A transaction asks a market of providers for FX quotes, selects the best one
  under a `Policy`, then accepts the quote, authorises and pays, failing over
  and compensating as needed. See `ActorTxSpike.Payment` for the lifecycle and
  `ActorTxSpike.Events` for the narrative stream.

  Transaction ids must not contain `:` (they prefix idempotency keys).
  """

  alias ActorTxSpike.{Payment, TransactionSupervisor}

  @doc """
  Submit a transaction. `request` needs `:amount`; `:id` is generated if absent.

  Options: `:providers` (`[%{id:, server:}]`), `:policy` (`ActorTxSpike.Policy`),
  and `:opts` (see `ActorTxSpike.Payment` defaults).
  """
  @spec submit(map(), keyword()) :: {:ok, String.t()} | {:error, term()}
  def submit(request, opts) when is_map(request) do
    id = request[:id] || generate_id()
    request = Map.put(request, :id, id)

    args = [
      id: id,
      request: request,
      providers: Keyword.fetch!(opts, :providers),
      policy: Keyword.fetch!(opts, :policy),
      opts: Keyword.get(opts, :opts, [])
    ]

    case TransactionSupervisor.start_payment(args) do
      {:ok, _pid} -> {:ok, id}
      {:error, {:already_started, _pid}} -> {:ok, id}
      {:error, _reason} = error -> error
    end
  end

  defdelegate await(id, timeout \\ 5_000), to: Payment
  defdelegate snapshot(id), to: Payment

  defp generate_id, do: "tx-" <> Base.encode16(:crypto.strong_rand_bytes(4), case: :lower)
end

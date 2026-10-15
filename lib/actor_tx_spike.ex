defmodule ActorTxSpike do
  @moduledoc """
  Public API of the transaction spike.

  A transaction asks a market of providers for FX quotes, selects the best one
  under a `Policy`, then accepts the quote, authorises and pays, failing over
  and compensating as needed. See `ActorTxSpike.Payment` for the lifecycle and
  `ActorTxSpike.Events` for the narrative stream.

  Transaction ids must not contain `:` (they prefix idempotency keys).
  """

  alias ActorTxSpike.{Payment, PolicySet, TransactionSupervisor}

  @doc """
  Submit a transaction. `request` needs `:amount`; `:id` is generated if absent.

  Options:

  * `:providers` (`[%{id:, server:}]`)
  * `:policy` (`ActorTxSpike.Policy`) **or** `:policy_set` (`ActorTxSpike.PolicySet`,
    resolved from the request's `:merchant_id` and `:customer_id`)
  * `:cutoffs` (`%{region => ActorTxSpike.Cutoff}`), used with the request's `:destination_region`
  * `:guarantees` (`%{merchant_id => ActorTxSpike.Guarantee}`) and `:ledger`
  * `:opts` (see `ActorTxSpike.Payment` defaults).
  """
  @spec submit(map(), keyword()) :: {:ok, String.t()} | {:error, term()}
  def submit(request, opts) when is_map(request) do
    id = request[:id] || generate_id()
    request = Map.put(request, :id, id)

    {policy, layers} = resolve_policy(opts, request)

    args =
      [
        id: id,
        request: request,
        providers: Keyword.fetch!(opts, :providers),
        policy: policy,
        policy_layers: layers,
        cutoff: opts |> Keyword.get(:cutoffs, %{}) |> Map.get(request[:destination_region]),
        guarantee: opts |> Keyword.get(:guarantees, %{}) |> Map.get(request[:merchant_id]),
        opts: Keyword.get(opts, :opts, [])
      ] ++ if(opts[:ledger], do: [ledger: opts[:ledger]], else: [])

    case TransactionSupervisor.start_payment(args) do
      {:ok, _pid} -> {:ok, id}
      {:error, {:already_started, _pid}} -> {:ok, id}
      {:error, _reason} = error -> error
    end
  end

  defp resolve_policy(opts, request) do
    case Keyword.fetch(opts, :policy_set) do
      {:ok, set} -> PolicySet.resolve(set, request)
      :error -> {Keyword.fetch!(opts, :policy), []}
    end
  end

  defdelegate await(id, timeout \\ 5_000), to: Payment
  defdelegate snapshot(id), to: Payment

  defp generate_id, do: "tx-" <> Base.encode16(:crypto.strong_rand_bytes(4), case: :lower)
end

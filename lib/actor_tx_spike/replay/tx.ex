defmodule ActorTxSpike.Replay.Tx do
  @moduledoc """
  Reads one transaction's records into a summary. Pure functions over the
  decoded log; used by replay, verification, what-if and comparison.
  """

  alias ActorTxSpike.{Money, Policy, Provider.Quote, Selector}

  @undo %{
    "accept_quote" => "cancel_accept",
    "authorise" => "void_authorisation",
    "pay" => "refund"
  }
  @states [:unknown, :healthy, :degraded, :stalled, :quarantined, :down]

  @doc "The undo operation that reverses a step, or nil."
  def undo_for(op), do: @undo[op]

  @doc "Summarise a transaction's records (as of the last record given)."
  @spec summarize([map()]) :: map()
  def summarize(records) do
    received = first(records, "tx_received")
    terminal = Enum.find(records, &(&1["kind"] in ~w(tx_completed tx_failed needs_review)))
    economics = first(records, "tx_economics")

    outcome =
      case terminal && terminal["kind"] do
        "tx_completed" -> :completed
        "tx_failed" -> :failed
        "needs_review" -> :needs_review
        nil -> :open
      end

    provider = final_provider(records, economics)

    %{
      id: received["tx"],
      request: received["data"]["request"],
      amount: received["data"]["request"]["amount"],
      started_at_ms: received["at"],
      ended_at_ms: terminal && terminal["at"],
      outcome: outcome,
      phase: records |> payment_phases() |> List.last(),
      provider: provider,
      steps: succeeded_ops(records, provider),
      net: provider && net_of(records, provider),
      margin: economics && economics["data"]["margin"]
    }
  end

  @doc "Records of one kind, in order."
  def all(records, kind), do: Enum.filter(records, &(&1["kind"] == kind))

  @doc "First record of a kind."
  def first(records, kind), do: Enum.find(records, &(&1["kind"] == kind))

  @doc "Operations that succeeded at a provider (a direct success or a reconcile)."
  def succeeded_ops(records, provider) do
    for %{"kind" => kind, "data" => d} <- records,
        kind in ~w(step_succeeded reconciled),
        d["provider"] == provider,
        do: d["op"]
  end

  @doc "The provider the transaction ended with (the last one chosen)."
  def final_provider(records, economics) do
    if economics && economics["data"]["provider"] do
      economics["data"]["provider"]
    else
      records |> all("provider_chosen") |> List.last() |> then(&(&1 && &1["data"]["provider"]))
    end
  end

  @doc "Net value (minor units) of a provider's recorded quote for this transaction."
  def net_of(records, provider) do
    request = first(records, "tx_received")["data"]["request"]

    case quote_for(records, provider) do
      nil -> nil
      %Quote{} = q -> Money.convert(request["amount"], q.rate) - q.fee
    end
  end

  @doc "The recorded quote of a provider, rebuilt as a `Quote`."
  def quote_for(records, provider) do
    Enum.find_value(records, fn
      %{
        "kind" => "quote_observed",
        "data" => %{"provider" => ^provider, "outcome" => "quote"} = d
      } ->
        to_quote(d)

      _ ->
        nil
    end)
  end

  @doc "Observations for `Selector.evaluate/3`, rebuilt from `quote_observed` records."
  def observations(records) do
    for %{"kind" => "quote_observed", "data" => d} <- records do
      %{
        provider_id: d["provider"],
        server: nil,
        state: state(d["state"]),
        result: result(d),
        latency_ms: d["latency_ms"],
        observed_at_ms: d["observed_at_ms"]
      }
    end
  end

  @doc """
  The policy recorded for the transaction. With no overrides it is rebuilt
  exactly as recorded (weights are not re-normalised, so scores reproduce to the
  last bit). With overrides, each top-level key replaces the recorded one and
  the result is validated like any new policy.
  """
  @spec policy(map(), map()) :: {:ok, Policy.t()} | {:error, [String.t()]}
  def policy(received, overrides) when map_size(overrides) == 0 do
    p = received["data"]["policy"]

    {:ok,
     %Policy{
       weights: %{
         cost: p["weights"]["cost"],
         speed: p["weights"]["speed"],
         reliability: p["weights"]["reliability"]
       },
       min_validity_ms: p["min_validity_ms"],
       max_latency_ms: p["max_latency_ms"],
       max_bps_from_best: p["max_bps_from_best"],
       exclude_states: Enum.map(p["exclude_states"], &state/1)
     }}
  end

  def policy(received, overrides) do
    received["data"]["policy"]
    |> Map.merge(Map.new(overrides, fn {k, v} -> {to_string(k), v} end))
    |> Policy.new()
  end

  @doc "Re-run the live selection rule over the recorded observations."
  def reselect(records, overrides \\ %{}) do
    received = first(records, "tx_received")

    with {:ok, policy} <- policy(received, overrides) do
      request = %{id: received["tx"], amount: received["data"]["request"]["amount"]}
      {:ok, Selector.evaluate(request, observations(records), policy)}
    end
  end

  # Phases the Payment reported, in order (every payment event carries one).
  defp payment_phases(records) do
    for %{"actor" => "payment", "data" => %{"phase" => phase}} <- records, do: phase
  end

  defp to_quote(d) do
    %Quote{
      provider: d["provider"],
      request_id: d["request_id"],
      rate: d["rate"],
      fee: d["fee"],
      expires_at_ms: d["expires_at_ms"]
    }
  end

  defp result(%{"outcome" => "quote"} = d), do: {:ok, to_quote(d)}
  defp result(%{"outcome" => "rejected", "reason" => r}), do: {:rejected, r}
  defp result(%{"outcome" => "error", "reason" => r}), do: {:error, r}
  defp result(_timeout), do: :timeout

  defp state(s), do: Enum.find(@states, :unknown, &(Atom.to_string(&1) == s))
end

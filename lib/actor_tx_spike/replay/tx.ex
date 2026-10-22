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
  @terminal_kinds %{
    "tx_completed" => :completed,
    "tx_failed" => :failed,
    "needs_review" => :needs_review
  }
  @states [:unknown, :healthy, :degraded, :stalled, :quarantined, :down]

  @doc "The undo operation that reverses a step, or nil."
  def undo_for(op), do: @undo[op]

  @doc "Summarise a transaction's records (as of the last record given)."
  @spec summarize([map()]) :: map()
  def summarize(records) do
    received = first(records, "tx_received")
    terminal = Enum.find(records, &Map.has_key?(@terminal_kinds, &1["kind"]))
    economics = first(records, "tx_economics")
    provider = final_provider(records, economics)

    %{
      id: received["tx"],
      request: received["data"]["request"],
      amount: received["data"]["request"]["amount"],
      started_at_ms: received["at"],
      ended_at_ms: field(terminal, "at"),
      outcome: outcome(terminal),
      phase: records |> payment_phases() |> List.last(),
      provider: provider,
      steps: succeeded_ops(records, provider),
      net: net_of(records, provider),
      margin: economics |> data() |> field("margin"),
      resolution: records |> all("review_resolved") |> List.last() |> data() |> field("outcome")
    }
  end

  defp outcome(nil), do: :open
  defp outcome(%{"kind" => kind}), do: Map.fetch!(@terminal_kinds, kind)

  # nil-tolerant reads: a missing record has no fields
  defp field(nil, _key), do: nil
  defp field(map, key), do: map[key]
  defp data(record), do: field(record, "data")

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
    economics |> data() |> field("provider") || last_chosen_provider(records)
  end

  defp last_chosen_provider(records) do
    records |> all("provider_chosen") |> List.last() |> data() |> field("provider")
  end

  @doc "Net value (minor units) of a provider's recorded quote for this transaction."
  def net_of(_records, nil), do: nil

  def net_of(records, provider) do
    case quote_for(records, provider) do
      nil -> nil
      %Quote{} = q -> Money.convert(request_amount(records), q.rate) - q.fee
    end
  end

  defp request_amount(records), do: first(records, "tx_received")["data"]["request"]["amount"]

  @doc "The recorded quote of a provider, rebuilt as a `Quote`."
  def quote_for(records, provider) do
    records
    |> quote_payloads()
    |> Enum.find_value(&(&1["provider"] == provider && quote_from(&1)))
  end

  @doc "Every quote the providers gave, by provider (the latest wins), rebuilt as `Quote`s."
  def quotes(records) do
    for d <- quote_payloads(records), into: %{}, do: {d["provider"], quote_from(d)}
  end

  defp quote_payloads(records) do
    for %{"kind" => "quote_observed", "data" => %{"outcome" => "quote"} = d} <- records, do: d
  end

  @doc """
  Selection rounds, in order. A transaction normally has one; a transaction
  resumed after a restart before it had chosen a provider has two. Each round is
  `%{records: [quote_observed...], selection: selection_made | nil}`.
  """
  def rounds(records) do
    records
    |> Enum.reduce([], fn
      %{"kind" => "select_started"}, acc ->
        [%{records: [], selection: nil} | acc]

      _record, [] ->
        []

      %{"kind" => "quote_observed"} = r, [round | rest] ->
        [%{round | records: [r | round.records]} | rest]

      %{"kind" => "selection_made"} = r, [round | rest] ->
        [%{round | selection: r} | rest]

      _other, acc ->
        acc
    end)
    |> Enum.reverse()
    |> Enum.map(&%{&1 | records: Enum.reverse(&1.records)})
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

  @doc """
  Re-run the live selection rule over the recorded observations of the first
  selection round (the one the transaction's first decision was made on).
  """
  def reselect(records, overrides \\ %{}) do
    received = first(records, "tx_received")

    with {:ok, policy} <- policy(received, overrides) do
      request = %{id: received["tx"], amount: received["data"]["request"]["amount"]}
      first_round = records |> rounds() |> List.first(%{records: []})
      {:ok, Selector.evaluate(request, observations(first_round.records), policy)}
    end
  end

  @doc "Re-run the selection rule over the observations of one round, under the recorded policy."
  def reselect_round(received, round) do
    {:ok, policy} = policy(received, %{})
    request = %{id: received["tx"], amount: received["data"]["request"]["amount"]}
    Selector.evaluate(request, observations(round.records), policy)
  end

  # Phases the Payment reported, in order (every payment event carries one).
  defp payment_phases(records) do
    for %{"actor" => "payment", "data" => %{"phase" => phase}} <- records, do: phase
  end

  @doc "A `Quote` from a recorded `quote_observed` payload."
  def quote_from(d) do
    %Quote{
      provider: d["provider"],
      request_id: d["request_id"],
      rate: d["rate"],
      fee: d["fee"],
      expires_at_ms: d["expires_at_ms"]
    }
  end

  defp result(%{"outcome" => "quote"} = d), do: {:ok, quote_from(d)}
  defp result(%{"outcome" => "rejected", "reason" => r}), do: {:rejected, r}
  defp result(%{"outcome" => "error", "reason" => r}), do: {:error, r}
  defp result(_timeout), do: :timeout

  defp state(s), do: Enum.find(@states, :unknown, &(Atom.to_string(&1) == s))
end

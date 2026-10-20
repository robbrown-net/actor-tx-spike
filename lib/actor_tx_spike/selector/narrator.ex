defmodule ActorTxSpike.Selector.Narrator do
  @moduledoc """
  Tells the story of a selection: the events a person watches and the journal
  records a replay reads. Everything here is a side effect; the decision itself
  lives in `ActorTxSpike.Selector.evaluate/3`, which has none.
  """

  alias ActorTxSpike.{Events, Journal, Money, Selection}
  alias ActorTxSpike.Provider.Quote

  @doc "Selection began: who is being asked, and how long they have."
  def started(request, providers, deadline_ms) do
    announce(
      request,
      :select_started,
      "asking #{length(providers)} provider(s) for quotes " <>
        "(deadline #{deadline_ms}ms): #{Enum.map_join(providers, ", ", & &1.id)}",
      %{providers: Enum.map(providers, & &1.id), deadline_ms: deadline_ms}
    )
  end

  @doc "A provider was not called because its health state is excluded."
  def skipped(request, {provider, state}) do
    announce(request, :provider_skipped, "not calling #{provider.id}: it is #{state}", %{
      provider: provider.id,
      state: state
    })
  end

  @doc "The judgement of one provider's answer."
  def item(request, {:candidate, c}) do
    announce(
      request,
      :quote_received,
      "#{c.provider_id} quoted rate #{Money.format_rate(c.quote.rate)}, fee #{Money.format(c.quote.fee)} (net #{Money.format(c.net)}, #{c.latency_ms}ms)",
      %{provider: c.provider_id, rate: c.quote.rate, fee: c.quote.fee, latency_ms: c.latency_ms}
    )
  end

  def item(request, {:excluded, c, reason}) do
    announce(request, :quote_excluded, "#{c.provider_id}'s quote is unusable: #{reason}", %{
      provider: c.provider_id,
      reason: reason
    })
  end

  def item(request, {:failed, obs, reason}) do
    announce(
      request,
      :quote_failed,
      "#{obs.provider_id} gave no quote: #{inspect(reason)} after #{obs.latency_ms}ms",
      %{provider: obs.provider_id, reason: inspect(reason), latency_ms: obs.latency_ms}
    )
  end

  @doc "What was decided."
  def outcome(request, %Selection{outcome: {:selected, best}} = sel) do
    announce(request, :selection_made, selected_message(best, sel.ranked), %{
      provider: best.provider_id,
      ranking: Enum.map(sel.ranked, &ranking_entry/1),
      excluded: describe_excluded(sel),
      elapsed_ms: sel.elapsed_ms
    })
  end

  def outcome(request, %Selection{outcome: {:fallback, baseline}} = sel) do
    announce(
      request,
      :selection_made,
      "no usable quote; falling back to baseline #{inspect(baseline)}",
      %{excluded: describe_excluded(sel)}
    )
  end

  def outcome(request, %Selection{outcome: :none} = sel) do
    announce(request, :selection_made, "no usable quote from any provider", %{
      excluded: describe_excluded(sel)
    })
  end

  @doc """
  Journal: the raw answer of one provider, with everything a later replay needs
  to judge it again (health state at call time, latency, when seen).
  """
  def observation(request, obs) do
    {kind, detail} = answer(obs.result)

    Journal.record(
      request.id,
      :quote_observed,
      "journal: #{obs.provider_id} answered the quote request (#{kind})",
      Map.merge(detail, %{
        provider: obs.provider_id,
        outcome: kind,
        state: obs.state,
        latency_ms: obs.latency_ms,
        observed_at_ms: obs.observed_at_ms
      })
    )
  end

  defp answer({:ok, %Quote{} = q}),
    do:
      {:quote,
       %{rate: q.rate, fee: q.fee, expires_at_ms: q.expires_at_ms, request_id: q.request_id}}

  defp answer({:rejected, why}), do: {:rejected, %{reason: inspect(why)}}
  defp answer({:error, why}), do: {:error, %{reason: inspect(why)}}
  defp answer(_timeout), do: {:timeout, %{}}

  defp selected_message(best, ranked) do
    "selected #{best.provider_id} (score #{Float.round(best.score, 3)}, " <>
      "net #{Money.format(best.net)}, #{best.latency_ms}ms)" <> ahead_of(ranked)
  end

  defp ahead_of([_best]), do: ""

  defp ahead_of([_best | others]),
    do:
      " ahead of " <>
        Enum.map_join(others, ", ", &"#{&1.provider_id} (#{Float.round(&1.score, 3)})")

  defp ranking_entry(c),
    do: %{provider: c.provider_id, score: c.score, net: c.net, latency_ms: c.latency_ms}

  defp describe_excluded(sel),
    do: Enum.map(sel.excluded, fn {id, why} -> %{provider: id, reason: inspect(why)} end)

  defp announce(request, kind, message, data),
    do: Events.emit(request.id, :selector, kind, message, data)
end

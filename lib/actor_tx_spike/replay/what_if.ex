defmodule ActorTxSpike.Replay.WhatIf do
  @moduledoc """
  Run a different strategy against a real day.

  For every transaction, the recorded provider answers are judged again under
  the recorded policy with some keys changed, using the same pure selection
  function the live system used. Each row compares three things: what the live
  policy would pick first (recomputed, so the comparison is like for like), what
  the changed policy picks, and what actually happened.

      WhatIf.run(day, %{"max_bps_from_best" => 25})
      WhatIf.run(day, %{"weights" => %{"cost" => 1}})

  What this can and cannot say:

    * It sees only providers that were actually asked. A provider that was
      skipped (stalled, quarantined) has no recorded answer, whatever the new
      policy would have done.
    * It assumes the alternative provider would have completed. Real day-of
      failures at the alternative are unknowable from this log.
    * Net is in minor units; the delta is what the *policy change alone* is
      worth on this day, measured on quote values.
  """

  alias ActorTxSpike.{Policy, Replay.Day, Replay.Tx}

  @spec run(Day.t(), map()) :: {:ok, map()} | {:error, [String.t()]}
  def run(%Day{} = day, overrides) when is_map(overrides) do
    # an override that is not a valid policy is refused up front, not silently skipped
    with {:ok, _} <- Policy.new(Map.merge(%{"weights" => %{"cost" => 1}}, overrides)) do
      {:ok, replay(day, overrides)}
    end
  end

  defp replay(day, overrides) do
    rows = for id <- day.tx_order, row = row(id, day.by_tx[id], overrides), do: row
    Map.merge(%{overrides: overrides, transactions: length(rows), rows: rows}, totals(rows))
  end

  # What the change is worth, over the transactions that have a price both ways.
  defp totals(rows) do
    comparable = Enum.filter(rows, &(&1.base_net && &1.alt_net))

    %{
      comparable: length(comparable),
      changed: Enum.count(comparable, & &1.changed?),
      lost_all_options: Enum.count(rows, &(&1.base && is_nil(&1.alt))),
      net_delta: comparable |> Enum.map(&(&1.alt_net - &1.base_net)) |> Enum.sum(),
      latency_delta_ms: comparable |> Enum.map(&(&1.alt_latency - &1.base_latency)) |> Enum.sum()
    }
  end

  defp row(id, rs, overrides), do: row(id, rs, overrides, Tx.all(rs, "quote_observed"))

  defp row(_id, _rs, _overrides, []), do: nil

  defp row(id, rs, overrides, _observed) do
    {:ok, base} = Tx.reselect(rs)
    {:ok, alt} = Tx.reselect(rs, overrides)
    summary = Tx.summarize(rs)
    {b, a} = {top(base), top(alt)}

    %{
      tx: id,
      outcome: summary.outcome,
      actual: summary.provider,
      actual_net: summary.net,
      base: b.id,
      base_net: b.net,
      base_latency: b.latency,
      alt: a.id,
      alt_net: a.net,
      alt_latency: a.latency,
      changed?: b.id != a.id
    }
  end

  # The provider a selection puts first, as plain values (nil fields when nobody is left).
  defp top(%{ranked: [c | _]}),
    do: %{id: to_string(c.provider_id), net: c.net, latency: c.latency_ms}

  defp top(%{ranked: []}), do: %{id: nil, net: nil, latency: nil}
end

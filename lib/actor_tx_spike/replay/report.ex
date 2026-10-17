defmodule ActorTxSpike.Replay.Report do
  @moduledoc "Plain-text renderings of replay results, for the terminal and for evidence."

  alias ActorTxSpike.Money

  @spec verify(map()) :: String.t()
  def verify(r) do
    head =
      "#{r.records} records, #{r.transactions} transactions " <>
        "(#{outcomes(r.outcomes)}): " <>
        if(r.ok?, do: "ALL RULES HELD", else: "#{length(r.violations)} VIOLATION(S)")

    Enum.join([head | Enum.map(r.violations, &violation/1)], "\n")
  end

  @spec state(map()) :: String.t()
  def state(s) do
    lines = [
      "as of #{inspect(s.as_of)}",
      "transactions: #{outcomes(s.outcomes)}; in flight: #{inspect(s.in_flight)}",
      "economics so far: saved #{Money.format(s.economics.saved_vs_median)}, margin #{Money.format(s.economics.margin)}, " <>
        "cost #{s.economics.cost_picos} pico-units over #{s.economics.transactions} transactions",
      "policy in force: #{inspect(s.policy)}",
      "operator actions so far: #{length(s.controls)}"
    ]

    providers =
      for {id, p} <- s.providers do
        h = p.health || %{}
        q = p.last_quote || %{}

        "  #{id}: #{h["state"] || "no health record"}, breaker #{h["breaker"] || "?"}, last quote #{inspect(Map.take(q, ["outcome", "rate", "fee", "latency_ms"]))}"
      end

    Enum.join(lines ++ ["providers:" | providers], "\n")
  end

  @spec what_if(map()) :: String.t()
  def what_if(w) do
    head =
      "what if #{inspect(w.overrides)}: #{w.changed} of #{w.comparable} transactions would route differently; " <>
        "net #{signed(w.net_delta)}, latency #{signed_ms(w.latency_delta_ms)} in total" <>
        if(w.lost_all_options > 0,
          do: "; #{w.lost_all_options} would have had no option at all",
          else: ""
        )

    changed =
      for row <- w.rows, row.changed? do
        "  #{row.tx}: #{row.base} -> #{row.alt} (net #{signed((row.alt_net || 0) - (row.base_net || 0))})"
      end

    Enum.join([head | changed], "\n")
  end

  @spec diff(map()) :: String.t()
  def diff(d) do
    head =
      "#{d.common} transactions in both, #{d.identical} identical, #{length(d.divergences)} divergence(s); " <>
        "only in A: #{length(d.only_a)}, only in B: #{length(d.only_b)}"

    lines =
      for x <- d.divergences, do: "  #{x.tx} #{x.field}: A=#{inspect(x.a)} B=#{inspect(x.b)}"

    Enum.join([head | lines], "\n")
  end

  defp violation(v), do: "  [#{v.rule}] #{v.tx || "-"} record #{v.n || "?"}: #{v.detail}"
  defp outcomes(o), do: o |> Enum.sort() |> Enum.map_join(", ", fn {k, n} -> "#{n} #{k}" end)
  defp signed(n), do: if(n > 0, do: "+", else: "") <> Money.format(n)
  defp signed_ms(n), do: if(n > 0, do: "+", else: "") <> "#{n}ms"
end

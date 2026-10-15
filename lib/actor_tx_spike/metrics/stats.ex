defmodule ActorTxSpike.Metrics.Stats do
  @moduledoc "Small pure statistics helpers for the metrics layer."

  @doc "Nearest-rank percentile of a list of numbers (`p` in 0..100); 0 for an empty list."
  @spec percentile([number()], number()) :: number()
  def percentile([], _p), do: 0

  def percentile(values, p) do
    sorted = Enum.sort(values)
    rank = max(ceil(p / 100 * length(sorted)), 1)
    Enum.at(sorted, rank - 1)
  end

  @doc "p50 / p95 / p99 / count / max of a list of numbers."
  @spec summary([number()]) :: %{
          p50: number(),
          p95: number(),
          p99: number(),
          max: number(),
          count: non_neg_integer()
        }
  def summary(values) do
    %{
      p50: percentile(values, 50),
      p95: percentile(values, 95),
      p99: percentile(values, 99),
      max: if(values == [], do: 0, else: Enum.max(values)),
      count: length(values)
    }
  end

  @doc "Mean of a list; 0.0 for an empty list."
  @spec mean([number()]) :: float()
  def mean([]), do: 0.0
  def mean(values), do: Enum.sum(values) / length(values)
end

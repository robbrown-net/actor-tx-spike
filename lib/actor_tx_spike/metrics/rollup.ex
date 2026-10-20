defmodule ActorTxSpike.Metrics.Rollup do
  @moduledoc """
  Pure one-minute rollups of raw samples: the "long-term" tier of the
  retention design (raw 1 s -> 1 min -> 1 h). Each bucket keeps the few numbers
  needed to chart a long window without keeping every second.
  """

  alias ActorTxSpike.Metrics.Stats

  @minute 60_000

  # How each series is summarised for a minute: output name => series
  @means [
    memory_total_mean: :memory_total,
    cpu_mean: :cpu,
    sched_mean: :sched,
    net_in_mean: :net_in,
    net_out_mean: :net_out,
    reductions_mean: :reductions
  ]
  @maxima [
    memory_total_max: :memory_total,
    cpu_max: :cpu,
    run_queue_max: :run_queue,
    e2e_p95_max: :e2e_p95
  ]
  @sums [completed: :completed, failed: :failed, needs_review: :needs_review]

  @spec minute(integer()) :: integer()
  def minute(at_ms), do: div(at_ms, @minute) * @minute

  @doc "Fold a raw sample into the bucket for its minute (start a new one when `bucket` is nil)."
  @spec add(map() | nil, map()) :: map()
  def add(nil, sample), do: add(%{at: minute(sample.at), n: 0, acc: %{}}, sample)

  def add(%{n: n, acc: acc} = bucket, sample) do
    acc =
      Enum.reduce(series(sample), acc, fn {k, v}, a -> Map.update(a, k, [v], &[v | &1]) end)

    %{bucket | n: n + 1, acc: acc}
  end

  @doc "Turn an accumulating bucket into its compact, chartable form."
  @spec finalize(map()) :: map()
  def finalize(%{at: at, n: n, acc: acc}) do
    %{at: at, samples: n}
    |> Map.merge(fold(acc, @means, &Stats.mean/1))
    |> Map.merge(fold(acc, @maxima, &Enum.max/1))
    |> Map.merge(fold(acc, @sums, &Enum.sum/1))
  end

  # {output name, series} pairs reduced with one function each
  defp fold(acc, spec, reduce), do: Map.new(spec, fn {name, key} -> {name, reduce.(acc[key])} end)

  defp series(s) do
    [
      memory_total: s.memory.total,
      cpu: s.cpu,
      sched: s.sched,
      run_queue: s.run_queue,
      net_in: s.net_in_bps,
      net_out: s.net_out_bps,
      reductions: s.reductions_ps,
      completed: s.tx.completed,
      failed: s.tx.failed,
      needs_review: s.tx.needs_review,
      e2e_p95: s.e2e.p95
    ]
  end
end

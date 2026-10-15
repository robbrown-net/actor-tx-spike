defmodule ActorTxSpike.Metrics.RollupTest do
  use ExUnit.Case, async: true

  alias ActorTxSpike.Metrics.Rollup

  defp sample(at, overrides \\ %{}) do
    Map.merge(
      %{
        at: at,
        memory: %{total: 1_000},
        cpu: 0.5,
        sched: 0.25,
        run_queue: 1,
        net_in_bps: 100,
        net_out_bps: 200,
        reductions_ps: 5_000,
        tx: %{completed: 1, failed: 0, needs_review: 0},
        e2e: %{p95: 120}
      },
      overrides
    )
  end

  test "minute/1 floors to the start of the minute" do
    assert Rollup.minute(125_999) == 120_000
    assert Rollup.minute(120_000) == 120_000
  end

  test "folds samples into a compact bucket" do
    bucket =
      nil
      |> Rollup.add(sample(120_100))
      |> Rollup.add(
        sample(121_100, %{
          memory: %{total: 3_000},
          cpu: 1.5,
          run_queue: 4,
          tx: %{completed: 2, failed: 1, needs_review: 1},
          e2e: %{p95: 300}
        })
      )

    assert %{at: 120_000, samples: 2} = done = Rollup.finalize(bucket)
    assert done.memory_total_mean == 2_000.0
    assert done.memory_total_max == 3_000
    assert done.cpu_mean == 1.0
    assert done.cpu_max == 1.5
    assert done.run_queue_max == 4
    assert done.completed == 3
    assert done.failed == 1
    assert done.needs_review == 1
    assert done.e2e_p95_max == 300
    assert done.net_in_mean == 100.0
    assert done.net_out_mean == 200.0
    assert done.reductions_mean == 5_000.0
    assert done.sched_mean == 0.25
  end
end

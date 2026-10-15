defmodule ActorTxSpike.Metrics.StatsTest do
  use ExUnit.Case, async: true

  alias ActorTxSpike.Metrics.Stats

  test "percentile uses nearest rank and tolerates unsorted input" do
    values = [50, 10, 40, 20, 30]
    assert Stats.percentile(values, 50) == 30
    assert Stats.percentile(values, 100) == 50
    assert Stats.percentile(values, 0) == 10
    assert Stats.percentile([], 95) == 0
  end

  test "summary and mean" do
    assert %{p50: 2, p95: 3, p99: 3, max: 3, count: 3} = Stats.summary([3, 1, 2])
    assert %{count: 0, max: 0, p95: 0} = Stats.summary([])
    assert Stats.mean([1, 2, 3]) == 2.0
    assert Stats.mean([]) == 0.0
  end
end

defmodule ActorTxSpike.ReaperTest do
  use ExUnit.Case, async: true

  alias ActorTxSpike.{Reaper, Wait}

  # Each test runs its own reaper under a unique name, so the application's store is untouched.
  defp reaper(opts) do
    name = :"reaper_#{System.unique_integer([:positive])}"
    start_supervised!({Reaper, Keyword.put(opts, :name, name)})
    name
  end

  test "a finished transaction's view is kept and answered by fetch and finished?" do
    name = reaper(sweep_ms: 100_000)

    refute Reaper.finished?("a", name)
    assert Reaper.fetch("a", name) == nil

    assert :ok = Reaper.finished("a", self(), %{result: {:ok, :done}}, name)

    assert Reaper.finished?("a", name)
    assert Reaper.fetch("a", name) == %{result: {:ok, :done}}
  end

  test "each sweep stops the finished processes, and only those" do
    supervisor = start_supervised!({DynamicSupervisor, strategy: :one_for_one})
    name = reaper(supervisor: supervisor, sweep_ms: 10)

    {:ok, finished} = DynamicSupervisor.start_child(supervisor, {Agent, fn -> :finished end})
    {:ok, running} = DynamicSupervisor.start_child(supervisor, {Agent, fn -> :running end})

    Reaper.finished("done", finished, %{result: :ok}, name)

    Wait.until!(fn -> not Process.alive?(finished) end)
    assert Process.alive?(running)
  end

  test "only the most recent finished transactions are kept; an older one is forgotten" do
    name = reaper(keep: 2, sweep_ms: 100_000)

    for id <- ["a", "b", "c"], do: Reaper.finished(id, self(), %{id: id}, name)

    Wait.until!(fn -> not Reaper.finished?("a", name) end)
    assert Reaper.fetch("b", name) == %{id: "b"}
    assert Reaper.fetch("c", name) == %{id: "c"}
  end
end

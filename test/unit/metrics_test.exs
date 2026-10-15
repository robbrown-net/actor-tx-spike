defmodule ActorTxSpike.MetricsTest do
  use ExUnit.Case, async: true

  alias ActorTxSpike.{Events, Metrics}

  defp start(opts \\ []) do
    {:ok, clock} = Agent.start_link(fn -> 1_700_000_000_000 end)
    advance = fn ms -> Agent.update(clock, &(&1 + ms)) end
    name = :"metrics_#{System.unique_integer([:positive])}"

    {:ok, pid} =
      Metrics.start_link(
        [
          name: name,
          interval_ms: :manual,
          subscribe: false,
          clock: fn -> Agent.get(clock, & &1) end
        ] ++
          opts
      )

    {pid, advance}
  end

  defp event(kind, tx, actor \\ :payment, data \\ %{}, at \\ 0) do
    {:tx_event,
     %Events.Event{
       seq: 1,
       at_ms: at,
       tx_id: tx,
       actor: actor,
       kind: kind,
       message: "m",
       data: data
     }}
  end

  test "a sample has resource, network, process and application sections" do
    {pid, advance} = start()
    first = Metrics.sample(pid)
    advance.(1_000)
    second = Metrics.sample(pid)

    assert first.memory.total > 0
    assert second.processes > 0
    assert second.sched >= 0.0 and second.cpu >= 0.0
    assert is_integer(second.reductions_ps) and second.reductions_ps >= 0
    assert second.overhead_us >= 0
    assert %{started: 0, in_flight: 0} = second.tx
    assert %{count: 0} = second.e2e
    assert [%{label: _} | _] = second.top
  end

  test "application counters and latency come from the narrative" do
    {pid, advance} = start()
    Metrics.sample(pid)

    send(pid, event(:tx_received, "t1", :payment, %{}, 1_000))
    send(pid, event(:tx_received, "t2", :payment, %{}, 1_000))
    send(pid, event(:failover, "t1"))
    send(pid, event(:tx_completed, "t1", :payment, %{}, 1_250))
    send(pid, event(:tx_failed, "t2", :payment, %{}, 1_900))
    send(pid, event(:tx_completed, "unknown-start", :payment, %{}, 2_000))
    send(pid, event(:needs_review, "t3", :payment, %{}, 2_000))
    send(pid, event(:received, "t1", {:provider, :a}))
    advance.(1_000)
    s = Metrics.sample(pid)

    assert %{started: 2, completed: 2, failed: 1, needs_review: 1, failovers: 1, in_flight: 0} =
             s.tx

    assert s.e2e.count == 2
    assert s.e2e.max == 900
    assert s.e2e.p50 == 250
  end

  test "provider health events are kept as the latest known state" do
    {pid, advance} = start()

    data = %{
      provider: :alpha,
      state: :degraded,
      p95_ms: 90,
      error_rate: 0.3,
      in_flight: 2,
      breaker: :closed,
      extra: :ignored
    }

    send(pid, event(:health, nil, {:provider, :alpha}, data))
    advance.(1_000)
    s = Metrics.sample(pid)

    assert s.providers == %{
             alpha: %{
               state: :degraded,
               p95_ms: 90,
               error_rate: 0.3,
               in_flight: 2,
               breaker: :closed
             }
           }
  end

  test "raw samples are bounded and snapshots can be limited" do
    {pid, advance} = start(raw_capacity: 3)

    for _ <- 1..5,
        do:
          (
            advance.(1_000)
            Metrics.sample(pid)
          )

    snap = Metrics.snapshot(pid)
    assert length(snap.raw) == 3
    assert snap.latest == List.last(snap.raw)
    assert length(Metrics.snapshot(pid, 2).raw) == 2
  end

  test "minute rollups are produced when the minute changes, and are bounded" do
    {pid, advance} = start(minute_capacity: 2)

    for _ <- 1..4 do
      Metrics.sample(pid)
      advance.(61_000)
    end

    Metrics.sample(pid)
    minutes = Metrics.snapshot(pid).minutes
    assert length(minutes) == 2
    assert Enum.all?(minutes, &(&1.samples >= 1 and &1.memory_total_max > 0))
  end

  test "the timer ticks on its own when an interval is given" do
    {:ok, pid} =
      Metrics.start_link(
        name: :"metrics_timer_#{System.unique_integer([:positive])}",
        interval_ms: 20
      )

    Process.sleep(120)
    assert length(Metrics.snapshot(pid).raw) >= 2
  end

  test "the global instance is running under the application" do
    assert %{raw: _, minutes: _} = Metrics.snapshot()
  end
end

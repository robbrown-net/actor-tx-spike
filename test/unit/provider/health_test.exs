defmodule ActorTxSpike.Provider.HealthTest do
  use ExUnit.Case, async: true

  alias ActorTxSpike.Provider.Health

  defp fail(h, n, now \\ 0),
    do: Enum.reduce(1..n, h, fn _, acc -> Health.record(acc, :error, 10, now) end)

  describe "state/1" do
    test "unknown with no observations" do
      assert Health.state(Health.new()) == :unknown
    end

    test "healthy after successful, fast calls (rejections are healthy answers)" do
      h = Health.new() |> Health.record(:success, 10, 0) |> Health.record(:rejected, 20, 1)
      assert Health.state(h) == :healthy
    end

    test "degraded on elevated error rate without opening the breaker" do
      h =
        Health.new(failure_threshold: 99)
        |> Health.record(:success, 10, 0)
        |> Health.record(:error, 10, 1)

      assert Health.state(h) == :degraded
    end

    test "degraded when p95 latency is slow" do
      h = Health.new(slow_ms: 100) |> Health.record(:success, 500, 0)
      assert Health.state(h) == :degraded
    end

    test "stalled when the breaker is open, degraded when half open" do
      open = fail(Health.new(failure_threshold: 2), 2)
      assert Health.state(open) == :stalled

      {:ok, half} = Health.checkout(open, 1_000_000)
      assert Health.state(half) == :degraded
    end

    test "quarantined wins" do
      {h, _} = Health.new(version: "1") |> Health.observe_version("2")
      assert Health.state(h) == :quarantined
    end
  end

  describe "stats/1" do
    test "empty window" do
      assert %{samples: 0, error_rate: +0.0, mean_ms: 0, p95_ms: 0} = Health.stats(Health.new())
    end

    test "rolling window keeps only the newest samples" do
      h =
        Enum.reduce(1..5, Health.new(window_size: 3), fn i, acc ->
          Health.record(acc, :success, i * 10, i)
        end)

      assert %{samples: 3, mean_ms: 40.0, p95_ms: 50, error_rate: +0.0} = Health.stats(h)
    end

    test "timeouts count as failures with their waited latency" do
      h = Health.new(failure_threshold: 99) |> Health.record(:timeout, 100, 0)
      assert %{error_rate: 1.0, p95_ms: 100} = Health.stats(h)
    end
  end

  describe "circuit breaker" do
    test "opens after consecutive failures; success resets the streak" do
      h = Health.new(failure_threshold: 3)
      h = h |> fail(2) |> Health.record(:success, 1, 0) |> fail(2)
      assert h.breaker == :closed

      h = fail(h, 1, 50)
      assert h.breaker == :open
      assert h.opened_at == 50
    end

    test "denies while open, admits one probe after cool-down" do
      h = fail(Health.new(failure_threshold: 1, cool_down_ms: 100), 1, 0)

      assert {:error, :circuit_open} = Health.checkout(h, 99)
      assert {:ok, probing} = Health.checkout(h, 100)
      assert probing.breaker == :half_open
      assert {:error, :circuit_open} = Health.checkout(probing, 101)
    end

    test "successful probe closes the breaker" do
      h = fail(Health.new(failure_threshold: 1, cool_down_ms: 0), 1, 0)
      {:ok, probing} = Health.checkout(h, 0)
      closed = Health.record(probing, :success, 5, 1)

      assert closed.breaker == :closed
      assert closed.consecutive_failures == 0
      assert [{:success, 5}] = closed.window
      assert Health.state(closed) == :degraded
      assert {:ok, _} = Health.checkout(closed, 2)
    end

    test "recovery is gradual: a streak of successes is required to be healthy" do
      h = fail(Health.new(failure_threshold: 5, cool_down_ms: 0, recovery_successes: 3), 5, 0)
      {:ok, probing} = Health.checkout(h, 0)
      h = Health.record(probing, :success, 5, 1)
      assert h.recovering == 2
      assert Health.state(h) == :degraded

      h = Health.record(h, :success, 5, 2)
      assert Health.state(h) == :degraded
      h = Health.record(h, :rejected, 5, 3)
      assert h.recovering == 0
      assert Health.state(h) == :healthy
    end

    test "a failure while recovering restarts the streak" do
      h = fail(Health.new(failure_threshold: 5, cool_down_ms: 0, recovery_successes: 3), 5, 0)
      {:ok, probing} = Health.checkout(h, 0)
      h = probing |> Health.record(:success, 5, 1) |> Health.record(:success, 5, 2)
      assert h.recovering == 1

      h = Health.record(h, :error, 5, 3)
      assert h.recovering == 3
      assert h.breaker == :closed
    end

    test "failed probe re-opens the breaker with a fresh timestamp" do
      h = fail(Health.new(failure_threshold: 1, cool_down_ms: 10), 1, 0)
      {:ok, probing} = Health.checkout(h, 10)
      reopened = Health.record(probing, :timeout, 5, 20)

      assert reopened.breaker == :open
      assert reopened.opened_at == 20
    end

    test "late results arriving while open do not change the breaker" do
      h = fail(Health.new(failure_threshold: 1), 1, 0)
      assert Health.record(h, :success, 1, 5).breaker == :open
      assert Health.record(h, :error, 1, 5).breaker == :open
    end

    test "abandoned probe frees the probe slot" do
      h = fail(Health.new(failure_threshold: 1, cool_down_ms: 0), 1, 0)
      {:ok, probing} = Health.checkout(h, 0)
      assert {:error, :circuit_open} = Health.checkout(probing, 1)

      assert {:ok, _} = probing |> Health.abandon() |> Health.checkout(2)
    end
  end

  describe "version drift" do
    test "first seen, unchanged, then drift quarantines until released" do
      h = Health.new()
      assert {h, :first_seen} = Health.observe_version(h, "1")
      assert {h, :unchanged} = Health.observe_version(h, "1")
      assert {h, {:drift, "1", "2"}} = Health.observe_version(h, "2")

      assert h.drift == {"1", "2"}
      assert {:error, :quarantined} = Health.checkout(h, 0)

      released = Health.release(h)
      assert released.drift == nil
      assert {:ok, _} = Health.checkout(released, 0)
    end
  end
end

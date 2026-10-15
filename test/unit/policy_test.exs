defmodule ActorTxSpike.PolicyTest do
  use ExUnit.Case, async: true

  alias ActorTxSpike.Policy

  describe "new/1" do
    test "empty data yields defaults" do
      assert {:ok, %Policy{exclude_states: [:stalled, :quarantined], min_validity_ms: 0}} =
               Policy.new(%{})
    end

    test "accepts string keys (decoded JSON) and normalises weights" do
      assert {:ok, p} =
               Policy.new(%{
                 "weights" => %{"cost" => 3, "speed" => 1},
                 "min_validity_ms" => 5_000,
                 "max_latency_ms" => 2_000,
                 "exclude_states" => ["stalled", "degraded"]
               })

      assert p.weights == %{cost: 0.75, speed: 0.25, reliability: 0.0}
      assert p.min_validity_ms == 5_000
      assert p.max_latency_ms == 2_000
      assert p.exclude_states == [:stalled, :degraded]
    end

    test "accepts atom keys and explicit nil max latency" do
      assert {:ok, %Policy{max_latency_ms: nil, weights: %{reliability: 1.0}}} =
               Policy.new(%{weights: %{reliability: 2}, max_latency_ms: nil})
    end

    test "collects every validation error" do
      assert {:error, errors} =
               Policy.new(%{
                 "bogus" => 1,
                 "weights" => %{"cost" => -1},
                 "min_validity_ms" => -5,
                 "max_latency_ms" => 0,
                 "exclude_states" => ["sleepy"]
               })

      assert length(errors) == 5
      assert "unknown key: bogus" in errors
    end

    test "weight validation variants" do
      assert {:error, [m]} = Policy.new(%{"weights" => %{"cost" => 0}})
      assert m =~ "must not all be zero"
      assert {:error, [m]} = Policy.new(%{"weights" => %{"luck" => 1}})
      assert m =~ "unknown weights"
      assert {:error, [m]} = Policy.new(%{"weights" => "heavy"})
      assert m =~ "must be a map"
    end

    test "other field type errors" do
      assert {:error, [m]} = Policy.new(%{"exclude_states" => "stalled"})
      assert m =~ "must be a list"
      assert {:error, [m]} = Policy.new(%{"max_latency_ms" => "fast"})
      assert m =~ "positive integer"
    end
  end

  test "exclude_state?/2 always excludes :down" do
    {:ok, p} = Policy.new(%{"exclude_states" => []})
    assert Policy.exclude_state?(p, :down)
    refute Policy.exclude_state?(p, :stalled)
    assert Policy.exclude_state?(%Policy{}, :stalled)
  end

  describe "check/2" do
    setup do
      {:ok, p} = Policy.new(%{"min_validity_ms" => 100, "max_latency_ms" => 50})
      {:ok, policy: p}
    end

    test "ok within constraints", %{policy: p} do
      assert :ok = Policy.check(p, %{validity_ms: 100, latency_ms: 50})
    end

    test "validity and latency violations", %{policy: p} do
      assert {:excluded, :quote_expires_too_soon} =
               Policy.check(p, %{validity_ms: 99, latency_ms: 1})

      assert {:excluded, :too_slow} = Policy.check(p, %{validity_ms: 500, latency_ms: 51})
    end

    test "no latency limit by default" do
      assert :ok = Policy.check(%Policy{}, %{validity_ms: 0, latency_ms: 1_000_000})
    end
  end

  describe "scores/2" do
    defp cand(net, latency, state), do: %{net: net, latency_ms: latency, state: state}

    test "empty candidates" do
      assert Policy.scores(%Policy{}, []) == []
    end

    test "cost-only weights rank by net" do
      {:ok, p} = Policy.new(%{"weights" => %{"cost" => 1}})

      assert [1.0, +0.0, 0.5] =
               Policy.scores(p, [
                 cand(110, 5, :healthy),
                 cand(100, 1, :healthy),
                 cand(105, 9, :healthy)
               ])
    end

    test "speed-only weights rank by latency (lower is better)" do
      {:ok, p} = Policy.new(%{"weights" => %{"speed" => 1}})
      assert [+0.0, 1.0] = Policy.scores(p, [cand(1, 100, :healthy), cand(1, 0, :healthy)])
    end

    test "reliability uses a fixed factor per state, unknown states score zero" do
      {:ok, p} = Policy.new(%{"weights" => %{"reliability" => 1}})

      assert [1.0, 0.7, 0.4, +0.0] =
               Policy.scores(p, [
                 cand(1, 1, :healthy),
                 cand(1, 1, :unknown),
                 cand(1, 1, :degraded),
                 cand(1, 1, :stalled)
               ])
    end

    test "equal candidates all score the full cost+speed share" do
      {:ok, p} = Policy.new(%{"weights" => %{"cost" => 1, "speed" => 1}})
      assert [1.0, 1.0] = Policy.scores(p, [cand(5, 5, :healthy), cand(5, 5, :healthy)])
    end
  end
end

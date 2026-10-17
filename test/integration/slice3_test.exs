defmodule ActorTxSpike.Slice3IntegrationTest do
  @moduledoc """
  Cascading policy, standing guarantees, regional cut-offs and settlement
  working together through the public API with real components (no stubs).
  """
  use ExUnit.Case, async: true

  alias ActorTxSpike.{Cutoff, Guarantee, Ledger, Policy, PolicySet}
  alias ActorTxSpike.Provider.{Fake, Server}

  @fast [backoff_ms: 5, reconcile_interval_ms: 5, step_timeout_ms: 500, deadline_ms: 500]

  defp market do
    specs = [
      cheap_slow: [rate: 1.10, latency_ms: 40],
      fast_dear: [rate: 1.06],
      middling: [rate: 1.08, latency_ms: 15]
    ]

    for {id, opts} <- specs do
      {:ok, fake} = Fake.start_link([id: id] ++ opts)

      {:ok, server} =
        Server.start_link(adapter: Fake, config: fake, id: id, health: [failure_threshold: 50])

      %{id: id, fake: fake, server: server}
    end
  end

  defp policy_set do
    {:ok, set} =
      PolicySet.new(%{
        "global" => %{"weights" => %{"cost" => 0.5, "speed" => 0.5}, "max_bps_from_best" => 1_000},
        "merchants" => %{
          "acme" => %{
            "overrides_allowed" => true,
            "policy" => %{"weights" => %{"cost" => 1.0, "speed" => 0.0}}
          }
        },
        "customers" => %{
          "rush" => %{"policy" => %{"weights" => %{"cost" => 0.0, "speed" => 1.0}}}
        }
      })

    set
  end

  test "one market, three objectives: global, merchant and customer routes differ and are explained" do
    m = market()
    providers = Enum.map(m, &%{id: &1.id, server: &1.server})
    set = policy_set()
    tag = System.unique_integer([:positive])

    go = fn suffix, request ->
      id = "s3-#{tag}-#{suffix}"

      {:ok, ^id} =
        ActorTxSpike.submit(Map.merge(%{id: id, amount: 1_000}, request),
          providers: providers,
          policy_set: set,
          opts: @fast
        )

      ActorTxSpike.await(id, 5_000)
    end

    assert {:ok, %{provider: provider, policy: "global"}} = go.("g", %{})
    assert provider in [:middling, :fast_dear, :cheap_slow]

    assert {:ok, %{provider: :cheap_slow, policy: "global + merchant:acme"}} =
             go.("m", %{merchant_id: "acme"})

    assert {:ok, %{provider: :fast_dear, policy: "global + customer:rush"}} =
             go.("c", %{customer_id: "rush"})

    assert {:ok, %{policy: "global + merchant:acme + customer:rush"}} =
             go.("mc", %{merchant_id: "acme", customer_id: "rush"})
  end

  test "guarantee margin accumulates in the ledger over a week of transactions" do
    m = market()
    providers = Enum.map(m, &%{id: &1.id, server: &1.server})
    {:ok, ledger} = Ledger.start_link(name: :"ledger_#{System.unique_integer([:positive])}")
    {:ok, g} = Guarantee.new(%{"merchant_id" => "acme", "period" => "2026-W42", "rate" => 1.09})
    {:ok, policy} = Policy.new(%{"weights" => %{"cost" => 1}})
    tag = System.unique_integer([:positive])

    ids =
      for n <- 1..6 do
        id = "gl-#{tag}-#{n}"

        {:ok, ^id} =
          ActorTxSpike.submit(%{id: id, amount: 1_000 * n, merchant_id: "acme"},
            providers: providers,
            policy: policy,
            guarantees: %{"acme" => g},
            ledger: ledger,
            opts: @fast
          )

        id
      end

    for id <- ids, do: assert({:ok, %{guarantee: %{margin: _}}} = ActorTxSpike.await(id, 5_000))

    # best rate is 1.10 vs guaranteed 1.09: a positive margin on every transaction
    assert %{count: 6, margin_total: margin, exposure_total: 0} =
             Ledger.summary(ledger, "acme", "2026-W42")

    assert margin == 210
    assert [%{merchant_id: "acme", period: "2026-W42", count: 6}] = Ledger.all(ledger)
  end

  test "regional cut-offs use local time and offset rules; a missed cut-off fails cleanly" do
    m = market()
    providers = Enum.map(m, &%{id: &1.id, server: &1.server})
    {:ok, policy} = Policy.new(%{"weights" => %{"cost" => 1}})

    # A real wall-clock cut-off in a far-future local time works like any other deadline.
    {:ok, uk} =
      Cutoff.new(%{
        "region" => "UK",
        "hour" => 18,
        "minute" => 0,
        "utc_offset_minutes" => fn _ -> 60 end
      })

    assert Cutoff.remaining_ms(uk, System.system_time(:millisecond)) in 1..86_400_000

    tag = System.unique_integer([:positive])
    id = "co-#{tag}"

    {:ok, ^id} =
      ActorTxSpike.submit(%{id: id, amount: 500, destination_region: "UK"},
        providers: providers,
        policy: policy,
        cutoffs: %{"UK" => uk},
        opts: @fast
      )

    assert {:ok, %{steps: steps}} = ActorTxSpike.await(id, 5_000)
    assert List.last(steps) == :settle

    # a cut-off that has just passed stops a new transaction before it does anything
    passed = Cutoff.fixed("EU", System.system_time(:millisecond) - 1)
    id2 = "co-#{tag}-late"

    {:ok, ^id2} =
      ActorTxSpike.submit(%{id: id2, amount: 500, destination_region: "EU"},
        providers: providers,
        policy: policy,
        cutoffs: %{"EU" => passed},
        opts: @fast
      )

    assert {:error, {:deadline_missed, "EU"}} = ActorTxSpike.await(id2, 5_000)
  end
end

defmodule ActorTxSpike.PolicySetTest do
  use ExUnit.Case, async: true

  alias ActorTxSpike.{Policy, PolicySet}

  @data %{
    "global" => %{
      "weights" => %{"cost" => 0.6, "speed" => 0.3, "reliability" => 0.1},
      "max_bps_from_best" => 100
    },
    "merchants" => %{
      "acme" => %{
        "overrides_allowed" => true,
        "policy" => %{"weights" => %{"cost" => 0.9}, "min_validity_ms" => 5_000}
      },
      "cheapskate" => %{
        "overrides_allowed" => false,
        "policy" => %{"weights" => %{"cost" => 1.0}}
      }
    },
    "customers" => %{
      "vip-1" => %{"policy" => %{"weights" => %{"speed" => 1.0}, "max_bps_from_best" => 25}}
    }
  }

  setup do
    {:ok, set} = PolicySet.new(@data)
    {:ok, set: set}
  end

  test "an unknown merchant/customer gets the global policy", %{set: set} do
    assert {%Policy{max_bps_from_best: 100} = p, [:global]} = PolicySet.resolve(set, %{})
    assert_in_delta p.weights.cost, 0.6, 1.0e-9

    assert {%Policy{max_bps_from_best: 100}, [:global]} =
             PolicySet.resolve(set, %{merchant_id: "nobody", customer_id: "nobody"})
  end

  test "an entitled merchant overrides partially and inherits the rest", %{set: set} do
    {p, layers} = PolicySet.resolve(set, %{merchant_id: "acme"})
    assert layers == [:global, {:merchant, "acme"}]
    assert p.min_validity_ms == 5_000
    assert p.max_bps_from_best == 100
    # cost raised to 0.9, speed 0.3 and reliability 0.1 inherited, then renormalised
    assert_in_delta p.weights.cost, 0.9 / 1.3, 1.0e-9
    assert_in_delta p.weights.speed, 0.3 / 1.3, 1.0e-9
  end

  test "a merchant that has not paid for overrides is ignored, and says so", %{set: set} do
    {p, layers} = PolicySet.resolve(set, %{merchant_id: "cheapskate"})
    assert layers == [:global, {:ignored, {:merchant, "cheapskate"}, :not_entitled}]
    assert_in_delta p.weights.cost, 0.6, 1.0e-9
  end

  test "a customer layer applies on top of the merchant layer", %{set: set} do
    {p, layers} = PolicySet.resolve(set, %{merchant_id: "acme", customer_id: "vip-1"})
    assert layers == [:global, {:merchant, "acme"}, {:customer, "vip-1"}]
    assert p.max_bps_from_best == 25
    assert p.min_validity_ms == 5_000
    assert p.weights.speed > p.weights.cost * 0.9
  end

  test "layers are described for humans", %{set: set} do
    {_p, layers} = PolicySet.resolve(set, %{merchant_id: "cheapskate", customer_id: "vip-1"})

    assert PolicySet.describe(layers) ==
             "global + merchant:cheapskate (ignored: not_entitled) + customer:vip-1"

    assert PolicySet.describe([:global, {:merchant, "a"}, {:customer, "b"}]) ==
             "global + merchant:a + customer:b"
  end

  test "bad layers are rejected up front with their location" do
    assert {:error, errors} =
             PolicySet.new(%{
               "global" => %{"weights" => %{"cost" => -1}},
               "merchants" => %{"m1" => %{"policy" => %{"max_latency_ms" => 0}}},
               "customers" => %{"c1" => %{"policy" => %{"bogus" => 1}}}
             })

    assert Enum.any?(errors, &String.starts_with?(&1, "global: weights"))
    assert Enum.any?(errors, &String.starts_with?(&1, "merchant:m1: max_latency_ms"))
    assert Enum.any?(errors, &String.starts_with?(&1, "customer:c1: unknown key"))
  end

  test "non-map sections are treated as empty rather than crashing" do
    assert {:ok, set} =
             PolicySet.new(%{"merchants" => nil, "customers" => "oops", "global" => nil})

    assert {%Policy{}, [:global]} = PolicySet.resolve(set, %{merchant_id: "m", customer_id: "c"})

    assert {:ok, set} =
             PolicySet.new(%{
               "merchants" => %{"m" => %{"overrides_allowed" => true, "policy" => nil}}
             })

    assert {%Policy{}, [:global, {:merchant, "m"}]} = PolicySet.resolve(set, %{merchant_id: "m"})
  end

  test "an empty definition is a valid global-defaults set; atom keys work" do
    assert {:ok, set} = PolicySet.new(%{})
    assert {%Policy{}, [:global]} = PolicySet.resolve(set, %{})

    assert {:ok, set} =
             PolicySet.new(%{
               global: %{max_bps_from_best: 10},
               merchants: %{m: %{overrides_allowed: true, policy: %{min_validity_ms: 1}}}
             })

    assert {%Policy{max_bps_from_best: 10, min_validity_ms: 1}, _} =
             PolicySet.resolve(set, %{merchant_id: :m})
  end
end

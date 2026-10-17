defmodule ActorTxSpike.EligibilityTest do
  use ExUnit.Case, async: true

  alias ActorTxSpike.Eligibility

  defp build(data) do
    {:ok, e} = Eligibility.new(data)
    e
  end

  @rules %{
    "default" => %{
      "instruments" => ["spot", "forward"],
      "customer_types" => ["business", "retail"]
    },
    "regions" => %{
      "IN" => %{
        "instruments" => ["spot"],
        "providers" => %{"allow" => ["northwind", "cobalt"]},
        "logs" => ["IN"],
        "local_only" => true,
        "retention_days" => 3650
      },
      "UK" => %{"retention_days" => 2555, "providers" => %{"deny" => ["zenith"]}},
      "US" => %{"customer_types" => ["business"], "logs" => ["GLOBAL", "US"]}
    }
  }

  test "an open eligibility permits everything and logs globally" do
    d = Eligibility.decide(Eligibility.open(), %{amount: 1})

    assert %{permitted?: true, reasons: [], regions: [], logs: ["GLOBAL"], retention_days: nil} =
             d

    assert d.instrument == :spot and d.customer_type == :business
    assert Eligibility.provider_allowed?(d, :anyone)
  end

  test "instrument and customer type are checked against every region involved" do
    e = build(@rules)

    assert %{permitted?: true} =
             Eligibility.decide(e, %{destination_region: "UK", instrument: :forward})

    d = Eligibility.decide(e, %{destination_region: "IN", instrument: :forward})
    refute d.permitted?
    assert d.reasons == ["forward is not permitted in IN"]

    # strictest wins on a corridor: UK allows forwards, IN does not
    d =
      Eligibility.decide(e, %{
        origin_region: "UK",
        destination_region: "IN",
        instrument: "forward"
      })

    assert d.reasons == ["forward is not permitted in IN"]

    d = Eligibility.decide(e, %{destination_region: "US", customer_type: "retail"})
    assert d.reasons == ["retail customers are not permitted in US"]

    assert %{permitted?: true} =
             Eligibility.decide(e, %{destination_region: "US", customer_type: :business})
  end

  test "providers: allow lists, deny lists, and all rules on a corridor apply together" do
    e = build(@rules)
    in_only = Eligibility.decide(e, %{destination_region: "IN"})
    assert Eligibility.provider_allowed?(in_only, :northwind)
    refute Eligibility.provider_allowed?(in_only, :bluebird)

    assert Eligibility.denial_reason(in_only, :bluebird) =~
             "bluebird is not eligible for the IN corridor"

    uk = Eligibility.decide(e, %{destination_region: "UK"})
    assert Eligibility.provider_allowed?(uk, "bluebird")
    refute Eligibility.provider_allowed?(uk, "zenith")

    both = Eligibility.decide(e, %{origin_region: "UK", destination_region: "IN"})
    assert Eligibility.provider_allowed?(both, :cobalt)
    refute Eligibility.provider_allowed?(both, :bluebird)
    refute Eligibility.provider_allowed?(both, :zenith)
  end

  test "records go to the union of logs, unless a local-only region is involved" do
    e = build(@rules)
    assert Eligibility.decide(e, %{destination_region: "UK"}).logs == ["GLOBAL"]
    assert Eligibility.decide(e, %{destination_region: "US"}).logs == ["GLOBAL", "US"]

    assert Eligibility.decide(e, %{origin_region: "UK", destination_region: "US"}).logs == [
             "GLOBAL",
             "US"
           ]

    # IN is local-only: the corridor's records stay in IN
    assert Eligibility.decide(e, %{origin_region: "US", destination_region: "IN"}).logs == ["IN"]
  end

  test "retention is the longest that applies; an unknown region uses the default" do
    e = build(@rules)

    assert Eligibility.decide(e, %{origin_region: "UK", destination_region: "IN"}).retention_days ==
             3650

    assert Eligibility.decide(e, %{destination_region: "UK"}).retention_days == 2555
    assert Eligibility.decide(e, %{destination_region: "BR"}).retention_days == nil
    assert Eligibility.decide(e, %{destination_region: "BR"}).logs == ["GLOBAL"]
  end

  test "rules are validated and every error is reported" do
    assert {:error, errors} =
             Eligibility.new(%{
               "bogus" => 1,
               "default" => %{
                 "instruments" => ["swap"],
                 "customer_types" => [],
                 "providers" => "some",
                 "logs" => ["bad name"],
                 "local_only" => "yes",
                 "retention_days" => 0,
                 "extra" => 1
               },
               "regions" => %{
                 "IN" => "nope",
                 "CN" => %{"providers" => %{"allow" => ["a"], "deny" => ["b"]}}
               }
             })

    text = Enum.join(errors, "\n")

    for part <- [
          "unknown key: bogus",
          "default.instruments",
          "default.customer_types",
          "default.providers",
          "default.logs",
          "default.local_only",
          "default.retention_days",
          "default: unknown key extra",
          "regions.IN: must be a map",
          "regions.CN.providers"
        ],
        do: assert(text =~ part, "missing #{part} in #{text}")

    assert {:error, [e]} = Eligibility.new(%{"regions" => %{"X" => %{"providers" => 5}}})
    assert e =~ "regions.X.providers"
    assert {:error, [_]} = Eligibility.new(%{"regions" => %{"X" => %{"logs" => []}}})
    assert {:ok, _} = Eligibility.new(%{})
    assert {:ok, _} = Eligibility.new(%{default: %{retention_days: nil, providers: "all"}})
  end
end

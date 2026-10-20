defmodule ActorTxSpike.Demo.Bulk.PlanTest do
  use ExUnit.Case, async: true

  alias ActorTxSpike.Demo.Bulk.Plan

  defp plan!(params) do
    assert {:ok, plan} = Plan.new(params)
    plan
  end

  defp commands(plan, n) do
    {list, _rand} =
      Enum.map_reduce(1..n, Plan.start(plan), fn _, rand -> Plan.next(plan, rand) end)

    list
  end

  test "a plan needs only what you want to change" do
    plan = plan!(%{})
    assert %{count: 10, mode: :fixed, interval_ms: 100, seed: nil, fixed: %{}} = plan
    assert plan.min <= plan.max
  end

  test "every problem is reported at once" do
    assert {:error, errors} =
             Plan.new(%{
               "count" => 0,
               "min_amount" => "abc",
               "max_amount" => -5,
               "interval_ms" => 70_000,
               "mode" => "chaos",
               "randomise" => ["merchant", "colour"],
               "fixed" => %{"flavour" => "x"},
               "seed" => "seven"
             })

    text = Enum.join(errors, "\n")

    for part <- ~w(count min_amount max_amount interval_ms mode randomise fixed seed),
        do: assert(text =~ part, "missing #{part} in #{text}")

    assert text =~ "colour"
    assert text =~ "flavour"
  end

  test "count and interval have limits, amounts may not be reversed" do
    assert {:error, [e]} = Plan.new(%{"count" => 10_001})
    assert e =~ "1 to 10000"
    assert {:ok, _} = Plan.new(%{"count" => 10_000, "interval_ms" => 0})
    assert {:ok, %{mode: :fixed, seed: nil}} = Plan.new(%{"mode" => "fixed", "seed" => nil})
    assert {:error, [e]} = Plan.new(%{"min_amount" => 50, "max_amount" => 10})
    assert e =~ "must not be below min_amount"
    assert {:error, _} = Plan.new(%{"min_amount" => nil})
    assert {:error, ["min_amount: must be a positive amount"]} = Plan.new(%{"min_amount" => 0})
    assert {:error, [~s(mode: must be "fixed" or "random")]} = Plan.new(%{"mode" => "chaos"})
    assert {:error, ["seed: must be a whole number or empty"]} = Plan.new(%{"seed" => "x"})
    assert {:error, _} = Plan.new(%{"randomise" => "all"})
    assert {:error, _} = Plan.new(%{"fixed" => "acme"})
  end

  test "amounts are drawn between the minimum and maximum, inclusive" do
    plan = plan!(%{"min_amount" => "10.00", "max_amount" => "10.05", "seed" => 1})
    amounts = plan |> commands(300) |> Enum.map(& &1["amount"]) |> Enum.uniq() |> Enum.sort()
    assert amounts == [10.0, 10.01, 10.02, 10.03, 10.04, 10.05]
  end

  test "equal minimum and maximum is a fixed amount" do
    plan = plan!(%{"min_amount" => 25, "max_amount" => 25})
    assert plan |> commands(5) |> Enum.map(& &1["amount"]) |> Enum.uniq() == [25.0]
  end

  test "a seeded plan repeats exactly; an unseeded one does not" do
    params = %{"mode" => "random", "seed" => 42, "max_amount" => 100_000}
    assert commands(plan!(params), 20) == commands(plan!(params), 20)
    refute commands(plan!(%{params | "seed" => 43}), 20) == commands(plan!(params), 20)

    unseeded = plan!(%{"mode" => "random", "max_amount" => 100_000})
    refute commands(unseeded, 20) == commands(unseeded, 20)
  end

  test "fixed mode varies only the amount; the fixed settings are used as given" do
    plan =
      plan!(%{
        "fixed" => %{"merchant" => "acme", "region" => "UK", "customer" => "", "origin" => nil},
        "seed" => 5
      })

    for cmd <- commands(plan, 30) do
      assert cmd["action"] == "submit"
      assert cmd["merchant"] == "acme" and cmd["region"] == "UK"
      refute Map.has_key?(cmd, "customer") or Map.has_key?(cmd, "origin")
      refute Map.has_key?(cmd, "instrument")
    end
  end

  test "random mode draws every setting from what the demo knows" do
    plan = plan!(%{"mode" => "random", "seed" => 9})
    cmds = commands(plan, 400)
    choices = Plan.choices()

    for cmd <- cmds, key <- Plan.settings() do
      assert cmd[key] in Map.fetch!(choices, key), "#{key}=#{inspect(cmd[key])}"
    end

    # nothing is stuck on one value
    for key <- ~w(merchant region instrument customer_type source_currency destination_currency) do
      assert cmds |> Enum.map(& &1[key]) |> Enum.uniq() |> length() > 1, key
    end
  end

  test "a payment never converts a currency into itself" do
    plan = plan!(%{"mode" => "random", "seed" => 3})

    for cmd <- commands(plan, 500),
        do: refute(cmd["source_currency"] == cmd["destination_currency"])
  end

  test "random mode randomises only the settings listed; the rest stay fixed" do
    plan =
      plan!(%{
        "mode" => "random",
        "randomise" => ["instrument", "customer_type"],
        "fixed" => %{"merchant" => "globex", "region" => "EU", "instrument" => "spot"},
        "seed" => 11
      })

    cmds = commands(plan, 200)
    assert Enum.all?(cmds, &(&1["merchant"] == "globex" and &1["region"] == "EU"))
    assert cmds |> Enum.map(& &1["instrument"]) |> Enum.uniq() |> Enum.sort() == ~w(forward spot)
    refute Enum.any?(cmds, &Map.has_key?(&1, "source_currency"))
  end

  test "a plan describes itself for the dashboard" do
    text = Plan.describe(plan!(%{"mode" => "random", "count" => 7, "seed" => 2}))
    assert text =~ "7 ×" and text =~ "random" and text =~ "seed 2"
    refute Plan.describe(plan!(%{})) =~ "seed"
  end
end

defmodule ActorTxSpike.CostsTest do
  use ExUnit.Case, async: true

  alias ActorTxSpike.Costs

  @gib 1_073_741_824

  defp usage(overrides) do
    Map.merge(
      %{
        reductions: 0,
        memory_bytes: 0,
        duration_ms: 0,
        provider_calls: 0,
        bytes_out: 0,
        bytes_in: 0,
        state_changes: 0,
        compensations: 0
      },
      overrides
    )
  end

  test "each resource is priced from the rate card" do
    card = Costs.new()

    assert %{cpu: 222_000, total: 222_000} = Costs.price(card, usage(%{reductions: 2_000_000}))

    assert %{memory: 1_389_000} =
             Costs.price(card, usage(%{memory_bytes: @gib, duration_ms: 1_000}))

    assert %{network: 90_000_000_000} = Costs.price(card, usage(%{bytes_out: @gib}))
    assert %{network: 0} = Costs.price(card, usage(%{bytes_in: @gib}))
    assert %{storage: 2_500_000} = Costs.price(card, usage(%{state_changes: 10}))
    assert %{provider_calls: 300_000_000} = Costs.price(card, usage(%{provider_calls: 6}))
  end

  test "the total is the sum of the parts" do
    u = %{
      reductions: 2_000_000,
      memory_bytes: @gib,
      duration_ms: 1_000,
      provider_calls: 6,
      bytes_out: @gib,
      bytes_in: @gib,
      state_changes: 10
    }

    assert %{total: 90_304_111_000} = Costs.price(Costs.new(), usage(u))
  end

  test "prices are overridable, so scenarios are one-liners" do
    doubled = Costs.new(network_picos_per_gib_out: 180_000_000_000, network_picos_per_gib_in: 5)

    assert %{network: 180_000_000_005} =
             Costs.price(doubled, usage(%{bytes_out: @gib, bytes_in: @gib}))
  end

  test "platform cost accrues with time, not traffic" do
    assert Costs.platform(Costs.new(), 3_600_000) == 83_200_000_000
    assert Costs.platform(Costs.new(), 1_800_000) == 41_600_000_000
    assert Costs.platform(Costs.new(), 0) == 0
  end

  test "a cent is ten million pico-units" do
    assert Costs.picos_per_minor() == 10_000_000_000
  end
end

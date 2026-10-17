defmodule ActorTxSpike.EconomicsTest do
  use ExUnit.Case, async: true

  alias ActorTxSpike.{Costs, Economics, Events}

  setup do
    name = :"economics_#{System.unique_integer([:positive])}"
    {:ok, pid} = Economics.start_link(name: name, subscribe: false, clock: fn -> 3_600_000 end)
    # a fixed clock, so no platform time elapses in these tests
    %{econ: name, pid: pid}
  end

  defp usage(overrides \\ %{}) do
    Map.merge(
      %{
        reductions: 1_000_000,
        memory_bytes: 0,
        duration_ms: 0,
        provider_calls: 6,
        bytes_out: 0,
        bytes_in: 0,
        state_changes: 10,
        compensations: 0
      },
      overrides
    )
  end

  defp event(tx, data) do
    {:tx_event,
     %Events.Event{
       seq: 1,
       at_ms: 0,
       tx_id: tx,
       actor: :economics,
       kind: :tx_economics,
       data: data
     }}
  end

  defp completed(provider, net, opts \\ []) do
    %{
      outcome: :completed,
      provider: provider,
      merchant: Keyword.get(opts, :merchant),
      amount: 100_000,
      usage: usage(),
      discovery: %{
        quotes: 3,
        chosen_net: net,
        median_net: Keyword.get(opts, :median, 10_000),
        worst_net: Keyword.get(opts, :worst, 9_000),
        best_net: Keyword.get(opts, :best, 11_000)
      },
      margin: Keyword.get(opts, :margin, 0),
      provider_fee: Keyword.get(opts, :fee, 150)
    }
  end

  test "completed transactions accumulate savings, margin and cost", %{econ: e, pid: pid} do
    send(pid, event("t1", completed(:cobalt, 11_000, margin: 500)))
    send(pid, event("t2", completed(:cobalt, 10_500, margin: -200)))
    %{overall: o} = Economics.summary(e)

    assert o.transactions == 2 and o.completed == 2
    assert o.saved_vs_median == 1_000 + 500
    assert o.saved_vs_worst == 2_000 + 1_500
    assert o.regret_vs_best == 0 + 500
    assert o.margin == 300
    assert o.provider_fees == 300
    # 1M reductions = 111_000, 10 state changes = 2_500_000, 6 calls = 300_000_000, twice
    assert o.cost.total == 2 * (111_000 + 2_500_000 + 300_000_000)
    assert o.benefit_picos == (1_500 + 300) * Costs.picos_per_minor()
    assert o.platform_picos == 0
    assert o.net_picos == o.benefit_picos - o.cost.total - o.platform_picos
  end

  test "failed and needs_review transactions cost money and earn none", %{econ: e, pid: pid} do
    base = %{completed(:cobalt, 0) | outcome: :failed, discovery: nil}
    send(pid, event("f1", base))
    send(pid, event("r1", %{base | outcome: :needs_review}))
    %{overall: o} = Economics.summary(e)

    assert %{failed: 1, needs_review: 1, completed: 0, transactions: 2} = o
    assert o.saved_vs_median == 0 and o.margin == 0 and o.provider_fees == 0
    assert o.cost.total > 0
    assert o.net_picos == -o.cost.total
  end

  test "a completed transaction without price discovery still counts its margin", %{
    econ: e,
    pid: pid
  } do
    send(pid, event("t1", %{completed(:cobalt, 0, margin: 70) | discovery: nil}))
    assert %{overall: %{margin: 70, saved_vs_median: 0, completed: 1}} = Economics.summary(e)
  end

  test "totals are kept per provider; providerless outcomes only count overall", %{
    econ: e,
    pid: pid
  } do
    send(pid, event("a", completed(:cobalt, 10_800)))
    send(pid, event("b", completed(:bluebird, 10_100)))
    send(pid, event("c", %{completed(:x, 0) | outcome: :failed, provider: nil, discovery: nil}))
    %{by_provider: bp, overall: o} = Economics.summary(e)

    assert Map.keys(bp) |> Enum.sort() == [:bluebird, :cobalt]
    assert bp.cobalt.saved_vs_median == 800
    assert bp.bluebird.saved_vs_median == 100
    assert o.transactions == 3
  end

  test "platform cost accrues with elapsed time" do
    {:ok, clock} = Agent.start_link(fn -> 0 end)

    {:ok, pid} =
      Economics.start_link(
        name: :econ_clock,
        subscribe: false,
        clock: fn -> Agent.get(clock, & &1) end
      )

    Process.unlink(pid)
    Agent.update(clock, fn _ -> 3_600_000 end)

    assert %{overall: %{platform_picos: 83_200_000_000, net_picos: -83_200_000_000}} =
             Economics.summary(:econ_clock)
  end

  test "each result is announced, and the rate card is visible", %{econ: e, pid: pid} do
    Events.subscribe()
    send(pid, event("narrated-1", completed(:cobalt, 11_000, margin: 500)))

    assert_receive {:tx_event, %{tx_id: "narrated-1", kind: :tx_costed, message: msg}}, 1_000
    assert msg =~ "saved +10.00 vs the median quote, margin +5.00"
    assert msg =~ "cost 0.0003026 to run"

    send(pid, event("narrated-2", %{completed(:cobalt, 0) | outcome: :failed, discovery: nil}))
    assert_receive {:tx_event, %{tx_id: "narrated-2", kind: :tx_costed, message: msg2}}, 1_000
    assert msg2 =~ "failed: no value earned"

    send(pid, event("narrated-3", completed(:cobalt, 9_000, margin: 0)))
    assert_receive {:tx_event, %{tx_id: "narrated-3", message: msg3}}, 1_000
    assert msg3 =~ "saved -10.00 vs the median quote, margin 0.00"

    assert %{costs: %{provider_call_picos: 50_000_000}} = Economics.summary(e)
  end

  test "other events are ignored; recent keeps a bounded tail", %{econ: e, pid: pid} do
    send(pid, {:tx_event, %Events.Event{seq: 1, kind: :step_started, tx_id: "x", data: %{}}})
    for n <- 1..205, do: send(pid, event("tx-#{n}", completed(:cobalt, 10_500)))
    assert %{overall: %{transactions: 205}} = Economics.summary(e)
    recent = Economics.recent(e, 500)
    assert length(recent) == 200
    assert List.last(recent).tx == "tx-205"
    assert [%{tx: "tx-204"}, %{tx: "tx-205"}] = Economics.recent(e, 2)
  end

  test "a custom rate card prices the same usage differently" do
    card =
      Costs.new(
        provider_call_picos: 0,
        state_change_picos: 0,
        cpu_picos_per_million_reductions: 1_000
      )

    {:ok, pid} = Economics.start_link(name: :econ_card, subscribe: false, costs: card)
    Process.unlink(pid)
    send(pid, event("t", completed(:cobalt, 10_000)))
    assert %{overall: %{cost: %{total: 1_000}}} = Economics.summary(:econ_card)
  end
end

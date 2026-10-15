defmodule ActorTxSpike.GuaranteeLedgerTest do
  use ExUnit.Case, async: true

  alias ActorTxSpike.{Guarantee, Ledger}

  describe "Guarantee" do
    test "validates and evaluates margin against the guarantee" do
      assert {:ok, g} =
               Guarantee.new(%{"merchant_id" => "acme", "period" => "2026-W42", "rate" => 1.08})

      assert %{guaranteed_net: 1080.0, achieved_net: 1085.0, margin: 5.0} =
               Guarantee.evaluate(g, 1000, 1085)

      assert %{margin: -10.0} = Guarantee.evaluate(g, 1000, 1070)
    end

    test "rejects bad input with every reason" do
      assert {:error, errors} =
               Guarantee.new(%{"merchant_id" => "", "period" => nil, "rate" => 0})

      assert length(errors) == 3
    end
  end

  describe "Ledger" do
    setup do
      {:ok, pid} = Ledger.start_link(name: :"ledger_#{System.unique_integer([:positive])}")
      {:ok, ledger: pid}
    end

    defp entry(tx, merchant, period, margin) do
      %{
        tx_id: tx,
        merchant_id: merchant,
        period: period,
        amount: 1000,
        guaranteed_net: 1000.0,
        achieved_net: 1000.0 + margin,
        margin: margin
      }
    end

    test "summarises margin, exposure, best and worst", %{ledger: l} do
      for {tx, m} <- [{"t1", 5.0}, {"t2", -3.0}, {"t3", 2.0}, {"t4", -1.0}] do
        assert :ok = Ledger.record(l, entry(tx, "acme", "W42", m))
      end

      s = Ledger.summary(l, "acme", "W42")
      assert %{count: 4, best: 5.0, worst: -3.0} = s
      assert_in_delta s.margin_total, 3.0, 1.0e-9
      assert_in_delta s.exposure_total, -4.0, 1.0e-9
      assert s.guaranteed_total == 4000.0
      assert_in_delta s.achieved_total, 4003.0, 1.0e-9
    end

    test "recording is idempotent per transaction", %{ledger: l} do
      Ledger.record(l, entry("t1", "acme", "W42", 5.0))
      Ledger.record(l, entry("t1", "acme", "W42", 99.0))
      assert %{count: 1, margin_total: 5.0} = Ledger.summary(l, "acme", "W42")
    end

    test "empty summaries are zeros; all/1 groups by merchant and period", %{ledger: l} do
      assert %{count: 0, margin_total: +0.0, exposure_total: +0.0, best: +0.0, worst: +0.0} =
               Ledger.summary(l, "nobody", "W1")

      Ledger.record(l, entry("a", "acme", "W42", 1.0))
      Ledger.record(l, entry("b", "globex", "W42", -2.0))
      Ledger.record(l, entry("c", "acme", "W43", 3.0))

      assert [{"acme", "W42"}, {"acme", "W43"}, {"globex", "W42"}] ==
               l |> Ledger.all() |> Enum.map(&{&1.merchant_id, &1.period})

      # a summary needs BOTH the merchant and the period to match
      assert %{count: 1, margin_total: 1.0} = Ledger.summary(l, "acme", "W42")
      assert %{count: 1, margin_total: -2.0} = Ledger.summary(l, "globex", "W42")
      assert %{count: 0} = Ledger.summary(l, "globex", "W43")
    end

    test "the global ledger is running under the application" do
      assert is_list(Ledger.all())
      assert %{count: _} = Ledger.summary("nobody-global", "W0")
    end
  end
end

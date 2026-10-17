defmodule ActorTxSpike.ReplayTest do
  @moduledoc """
  Replay is tested against real days: transactions run through the real system
  with the audit log on, then the log is loaded back. Rule checks are proven by
  breaking a good day in exactly one way and expecting exactly that violation.
  """
  use ExUnit.Case, async: true

  alias ActorTxSpike.{Audit, Guarantee, Journal, Ledger, Policy, Replay}
  alias ActorTxSpike.Provider.{Fake, Server}
  alias ActorTxSpike.Replay.{Compare, Report, Rules, Tx, WhatIf}

  @fast [backoff_ms: 5, reconcile_interval_ms: 5, step_timeout_ms: 500, deadline_ms: 500]

  # ---- fixtures: record a real day ---------------------------------------

  defp market(overrides \\ %{}, replace? \\ false) do
    base = %{best: [rate: 1.10, latency_ms: 20], second: [rate: 1.05], third: [rate: 1.00]}
    specs = if replace?, do: overrides, else: Map.merge(base, overrides)

    for {id, opts} <- specs do
      {:ok, fake} = Fake.start_link([id: id] ++ opts)

      {:ok, server} =
        Server.start_link(adapter: Fake, config: fake, id: id, health: [failure_threshold: 50])

      %{id: id, server: server}
    end
  end

  defp run(market, id, request, extra \\ []) do
    {:ok, policy} =
      Policy.new(%{"weights" => %{"cost" => 0.7, "speed" => 0.3}, "max_bps_from_best" => 1_000})

    {:ok, ^id} =
      ActorTxSpike.submit(
        Map.put(request, :id, id),
        [providers: market, policy: policy, opts: Keyword.merge(@fast, extra[:opts] || [])] ++
          Keyword.take(extra, [:guarantees, :ledger])
      )

    ActorTxSpike.await(id, 5_000)
  end

  defp wait_costed(path, ids) do
    done = fn ->
      File.exists?(path) and
        path
        |> File.stream!()
        |> Enum.map(&JSON.decode!/1)
        |> Enum.count(&(&1["kind"] == "tx_costed" and &1["tx"] in ids)) == length(ids)
    end

    Enum.reduce_while(1..200, nil, fn _, _ ->
      if done.(), do: {:halt, :ok}, else: Process.sleep(20) && {:cont, nil}
    end)
  end

  # One good day: happy + guarantee, failover, needs_review, failed.
  defp record_day do
    dir = Path.join(System.tmp_dir!(), "replay-#{System.unique_integer([:positive])}")
    path = Path.join(dir, "day.log")

    {:ok, audit} =
      Audit.start_link(path: path, name: :"replay_audit_#{System.unique_integer([:positive])}")

    Process.unlink(audit)
    tag = System.unique_integer([:positive])
    id = fn s -> "rp-#{tag}-#{s}" end

    {:ok, ledger} = Ledger.start_link(name: :"rp_ledger_#{tag}")
    {:ok, g} = Guarantee.new(%{"merchant_id" => "acme", "period" => "W1", "rate" => 1.05})

    run(market(), id.("happy"), %{amount: 100_000, merchant_id: "acme"},
      guarantees: %{"acme" => g},
      ledger: ledger
    )

    run(market(%{best: [rate: 1.10, authorise_mode: :reject]}), id.("failover"), %{
      amount: 200_000
    })

    run(market(%{best: [rate: 1.10, pay_mode: :timeout]}), id.("review"), %{amount: 300_000},
      opts: [reconcile_attempts: 2]
    )

    refusing = %{
      a: [rate: 1.1, accept_quote_mode: :reject],
      b: [rate: 1.0, accept_quote_mode: :reject]
    }

    run(market(refusing, true), id.("failed"), %{amount: 400_000})

    mixed = %{
      a: [rate: 1.1],
      b: [rate: 1.2, quote_mode: :reject],
      c: [rate: 1.3, quote_mode: {:error, :down}],
      d: [rate: 1.4, quote_mode: :timeout]
    }

    run(market(mixed, true), id.("mixed"), %{amount: 500_000}, opts: [deadline_ms: 150])

    ids = Enum.map(~w(happy failover review failed mixed), id)
    wait_costed(path, ids)
    GenServer.stop(audit)

    records =
      path
      |> File.stream!()
      |> Enum.map(&JSON.decode!/1)
      |> Enum.filter(&(&1["tx"] in ids or &1["kind"] == "health"))

    on_exit(fn -> File.rm_rf(dir) end)
    %{path: path, ids: ids, records: records, day: Replay.from_records(records)}
  end

  setup_all do
    # one recorded day, shared (read-only) by every test
    {:ok, fixture: record_day()}
  end

  # break a good day in one way
  defp mutate(%{records: records}, fun), do: Replay.from_records(fun.(records))

  defp edit(records, pred, fun),
    do: Enum.map(records, fn r -> if pred.(r), do: fun.(r), else: r end)

  defp drop(records, pred), do: Enum.reject(records, pred)
  defp kind(k, tx), do: fn r -> r["kind"] == k and String.ends_with?(r["tx"] || "", tx) end

  defp rules(day),
    do: day |> Rules.verify() |> Map.fetch!(:violations) |> Enum.map(& &1.rule) |> Enum.uniq()

  # ---- the good day ------------------------------------------------------

  test "a real day loads, summarises and satisfies every rule", %{fixture: f} do
    assert {:ok, day} = Replay.load(f.path)
    assert Enum.all?(f.ids, &(&1 in day.tx_order))

    result = Rules.verify(f.day)
    assert result.ok?, inspect(result.violations)
    assert result.transactions == 5
    assert %{completed: 3, failed: 1, needs_review: 1} = result.outcomes

    [happy, failover, review, failed, mixed] = Replay.transactions(f.day)
    assert %{outcome: :completed, provider: "a"} = mixed

    assert %{outcome: :completed, provider: "best", steps: ~w(accept_quote authorise pay settle)} =
             happy

    assert happy.net == 110_000 and happy.margin == 5_000
    assert %{outcome: :completed, provider: "second"} = failover
    assert %{outcome: :needs_review, provider: "best"} = review
    assert %{outcome: :failed, steps: []} = failed
    assert Replay.tx(f.day, "no-such-tx") == nil
    assert Replay.tx(f.day, hd(f.ids)).id == hd(f.ids)
    assert Replay.terminal_kinds() == ~w(tx_completed tx_failed needs_review)
  end

  test "the journal carries every input needed to replay", %{fixture: f} do
    happy = f.day.by_tx[hd(f.ids)]
    received = Tx.first(happy, "tx_received")

    assert %{"request" => %{"amount" => 100_000}, "policy" => %{"max_bps_from_best" => 1_000}} =
             received["data"]

    assert received["data"]["guarantee"]["rate"] == 105_000_000
    assert received["data"]["config"]["max_attempts"]

    assert received["data"]["providers"] == ["best", "second", "third"] or
             length(received["data"]["providers"]) == 3

    observed = Tx.all(happy, "quote_observed")
    assert length(observed) == 3

    assert Enum.all?(
             observed,
             &(&1["data"]["outcome"] == "quote" and &1["data"]["expires_at_ms"] > 0)
           )

    responses = Tx.all(happy, "provider_response")
    assert Enum.map(responses, & &1["data"]["op"]) == ~w(accept_quote authorise pay settle)
    assert Enum.all?(responses, &(&1["data"]["class"] == "ok" and &1["data"]["latency_ms"] >= 0))
    assert hd(responses)["data"]["key"] == hd(f.ids) <> ":best:accept"
  end

  # ---- state at a moment -------------------------------------------------

  test "state_at reconstructs the conditions at any record, time or sequence", %{fixture: f} do
    [happy_id | _] = f.ids
    completed = Enum.find(f.records, &(&1["kind"] == "tx_completed" and &1["tx"] == happy_id))

    early = Replay.state_at(f.day, {:n, Tx.first(f.day.by_tx[happy_id], "selection_made")["n"]})
    assert early.in_flight == [happy_id]
    assert early.outcomes == %{open: 1}
    assert early.policy["max_bps_from_best"] == 1_000
    assert early.guarantee["rate"] == 105_000_000
    assert early.config["max_attempts"]
    assert early.providers["best"].last_quote["outcome"] == "quote"
    assert early.economics.transactions == 0
    assert early.controls == []

    mid = Replay.state_at(f.day, {:at_ms, completed["at"]})
    assert mid.outcomes[:completed] == 1
    assert mid.as_of.last_at_ms <= completed["at"]

    done = Replay.state_at(f.day, {:seq, completed["seq"] + 10_000})
    assert done.in_flight == []
    assert done.outcomes == %{completed: 3, failed: 1, needs_review: 1}
    assert done.economics.transactions == 5
    assert done.economics.saved_vs_median != 0
    assert done.economics.cost_picos > 0

    nothing = Replay.state_at(f.day, {:n, 0})
    assert nothing.transactions == %{}
    assert nothing.policy == nil and nothing.as_of.last_n == nil
  end

  test "state_at records operator actions and health as they happened" do
    records = [
      %{
        "n" => 1,
        "seq" => 1,
        "at" => 10,
        "tx" => nil,
        "kind" => "control",
        "data" => %{"command" => %{"action" => "pace"}}
      },
      %{
        "n" => 2,
        "seq" => 2,
        "at" => 20,
        "tx" => nil,
        "kind" => "health",
        "data" => %{
          "provider" => "p",
          "state" => "stalled",
          "breaker" => "open",
          "error_rate" => 1.0,
          "p95_ms" => 9,
          "version" => "1",
          "recovering" => 0
        }
      },
      %{
        "n" => 3,
        "seq" => 3,
        "at" => 30,
        "tx" => nil,
        "kind" => "health",
        "data" => %{
          "provider" => "p",
          "state" => "healthy",
          "breaker" => "closed",
          "error_rate" => 0.0,
          "p95_ms" => 5,
          "version" => "1",
          "recovering" => 0
        }
      }
    ]

    day = Replay.from_records(records)
    s = Replay.state_at(day, {:at_ms, 25})
    assert [%{at: 10, command: %{"action" => "pace"}}] = s.controls
    assert s.providers["p"].health["state"] == "stalled"
    assert s.providers["p"].last_quote == nil
    assert Replay.state_at(day, {:at_ms, 35}).providers["p"].health["state"] == "healthy"
  end

  # ---- loading -----------------------------------------------------------

  test "loading refuses a missing or tampered log, and can skip the check", %{fixture: f} do
    assert {:error, :no_such_log} = Replay.load("/nonexistent/day.log")

    dir = Path.join(System.tmp_dir!(), "replay-bad-#{System.unique_integer([:positive])}")
    File.mkdir_p!(dir)
    bad = Path.join(dir, "bad.log")
    on_exit(fn -> File.rm_rf(dir) end)

    File.write!(
      bad,
      String.replace(File.read!(f.path), ~s("amount":100000), ~s("amount":1), global: false)
    )

    assert {:error, {:integrity, {:line, _, :hash_mismatch}}} = Replay.load(bad)
    assert {:ok, _day} = Replay.load(bad, verify: false)
  end

  # ---- rules: each one catches its violation ------------------------------

  test "single_pay: a second successful pay is caught", %{fixture: f} do
    day =
      mutate(f, fn rs ->
        pay = Enum.find(rs, &(&1["kind"] == "step_succeeded" and &1["data"]["op"] == "pay"))
        rs ++ [pay]
      end)

    assert :single_pay in rules(day)
  end

  test "completed_is_whole: a completed transaction missing a step is caught", %{fixture: f} do
    day =
      mutate(f, fn rs ->
        drop(rs, fn r ->
          r["kind"] == "step_succeeded" and r["data"]["op"] == "authorise" and
            r["tx"] == hd(f.ids)
        end)
      end)

    assert :completed_is_whole in rules(day)
  end

  test "failed_clean: a failover that left an effect un-undone is caught", %{fixture: f} do
    failover = Enum.at(f.ids, 1)

    day =
      mutate(f, fn rs -> drop(rs, fn r -> r["kind"] == "undone" and r["tx"] == failover end) end)

    assert :failed_clean in rules(day)
  end

  test "no_failover_after_doubt: moving on after pay was in doubt is caught", %{fixture: f} do
    review = Enum.at(f.ids, 2)

    day =
      mutate(f, fn rs ->
        doubt = Enum.find(rs, &(&1["kind"] == "in_doubt" and &1["tx"] == review))
        rs ++ [%{doubt | "kind" => "failover", "n" => 9_999}]
      end)

    assert :no_failover_after_doubt in rules(day)
  end

  test "idempotency_keys: a wrong key and a changing key are caught", %{fixture: f} do
    wrong =
      mutate(f, fn rs ->
        edit(rs, kind("step_started", "happy"), &put_in(&1, ["data", "key"], "oops"))
      end)

    assert :idempotency_keys in rules(wrong)

    vary =
      mutate(f, fn rs ->
        s =
          Enum.find(
            rs,
            &(&1["kind"] == "step_started" and &1["data"]["op"] == "pay" and &1["tx"] == hd(f.ids))
          )

        rs ++ [put_in(s, ["data", "key"], "other")]
      end)

    assert :idempotency_keys in rules(vary)
  end

  test "selection_reproducible: a ranking the inputs cannot produce is caught", %{fixture: f} do
    day =
      mutate(f, fn rs ->
        edit(rs, kind("selection_made", "happy"), fn r ->
          update_in(r, ["data", "ranking"], &Enum.reverse/1)
        end)
      end)

    assert :selection_reproducible in rules(day)
  end

  test "quote_valid_at_accept: accepting an expired quote is caught", %{fixture: f} do
    day =
      mutate(f, fn rs ->
        edit(
          rs,
          fn r ->
            r["kind"] == "quote_observed" and String.ends_with?(r["tx"], "happy") and
              r["data"]["provider"] == "best"
          end,
          &put_in(&1, ["data", "expires_at_ms"], 1)
        )
      end)

    assert :quote_valid_at_accept in rules(day)
  end

  test "deadline_respected: a step after the cut-off is caught", %{fixture: f} do
    day =
      mutate(f, fn rs ->
        received = Enum.find(rs, &(&1["kind"] == "tx_received" and &1["tx"] == hd(f.ids)))

        deadline = %{
          received
          | "kind" => "deadline_set",
            "data" => %{"deadline_ms" => received["at"] - 10_000}
        }

        [deadline | rs]
      end)

    assert :deadline_respected in rules(day)

    on_time =
      mutate(f, fn rs ->
        received = Enum.find(rs, &(&1["kind"] == "tx_received" and &1["tx"] == hd(f.ids)))

        [
          %{
            received
            | "kind" => "deadline_set",
              "data" => %{"deadline_ms" => received["at"] + 60_000}
          }
          | rs
        ]
      end)

    refute :deadline_respected in rules(on_time)
  end

  test "margin_arithmetic: a margin that does not follow from the quotes is caught", %{fixture: f} do
    day =
      mutate(f, fn rs ->
        edit(rs, &(&1["kind"] == "guarantee_margin"), &put_in(&1, ["data", "margin"], 1))
      end)

    assert :margin_arithmetic in rules(day)
  end

  test "economics_consistent: a chosen net that is not the quote's is caught", %{fixture: f} do
    day =
      mutate(f, fn rs ->
        edit(
          rs,
          kind("tx_economics", "happy"),
          &put_in(&1, ["data", "discovery", "chosen_net"], 1)
        )
      end)

    assert :economics_consistent in rules(day)
  end

  test "economics_consistent: ignores a completed transaction with no price discovery", %{
    fixture: f
  } do
    day =
      mutate(f, fn rs ->
        edit(rs, kind("tx_economics", "happy"), &put_in(&1, ["data", "discovery"], nil))
      end)

    refute :economics_consistent in rules(day)
  end

  test "the journal records every kind of quote answer", %{fixture: f} do
    mixed = f.day.by_tx[List.last(f.ids)]

    outcomes =
      mixed
      |> Tx.all("quote_observed")
      |> Map.new(&{&1["data"]["provider"], &1["data"]["outcome"]})

    assert outcomes == %{"a" => "quote", "b" => "rejected", "c" => "error", "d" => "timeout"}
    assert {:ok, %{ranked: [%{provider_id: "a"}], excluded: excluded}} = Tx.reselect(mixed)
    assert length(excluded) == 3
    assert Journal.journal?(:journal) and Journal.journal?("journal")
    refute Journal.journal?(:payment)
  end

  test "time_ordered: time running backwards is caught", %{fixture: f} do
    day =
      mutate(f, fn rs ->
        List.update_at(rs, 5, &Map.put(&1, "at", 1))
      end)

    assert :time_ordered in rules(day)
  end

  test "a transaction still open is reported as open, not as a violation", %{fixture: f} do
    day =
      mutate(f, fn rs ->
        drop(
          rs,
          &(&1["kind"] in ~w(tx_completed tx_economics tx_costed) and &1["tx"] == hd(f.ids))
        )
      end)

    result = Rules.verify(day)
    assert result.outcomes[:open] == 1
    refute :single_pay in rules(day)
  end

  # ---- what if -----------------------------------------------------------

  test "what-if re-judges the recorded answers under a changed policy", %{fixture: f} do
    assert {:ok, same} = WhatIf.run(f.day, %{})
    assert same.changed == 0 and same.net_delta == 0

    # price only: the cheapest quote wins whatever the speed
    assert {:ok, cheapest} = WhatIf.run(f.day, %{"weights" => %{"cost" => 1}})
    assert cheapest.comparable >= 3
    assert cheapest.net_delta >= 0

    # speed only: best (20ms) is no faster than the others, so nothing improves on net
    assert {:ok, strict} = WhatIf.run(f.day, %{"max_bps_from_best" => 0})
    assert strict.transactions >= 3

    # demand more quote life than any provider offers: nothing is left to choose
    assert {:ok, none} = WhatIf.run(f.day, %{"min_validity_ms" => 10_000_000})
    assert none.lost_all_options > 0
    assert Enum.any?(none.rows, &(&1.alt == nil))
  end

  test "an invalid policy change is refused, not silently ignored", %{fixture: f} do
    assert {:error, [message]} = WhatIf.run(f.day, %{"max_bps_from_best" => -1})
    assert message =~ "max_bps_from_best"
  end

  test "a day without recorded quotes has nothing to re-judge" do
    day =
      Replay.from_records([
        %{
          "n" => 1,
          "seq" => 1,
          "at" => 1,
          "tx" => "t",
          "kind" => "tx_received",
          "data" => %{"request" => %{"amount" => 1}, "policy" => %{}}
        }
      ])

    assert {:ok, %{transactions: 0, rows: []}} = WhatIf.run(day, %{"max_bps_from_best" => 5})
  end

  # ---- comparing two days ------------------------------------------------

  test "a day is identical to itself; differences are located", %{fixture: f} do
    same = Compare.diff(f.day, f.day)
    assert same.common == 5 and same.identical == 5 and same.divergences == []

    [happy | _] = f.ids

    other =
      mutate(f, fn rs ->
        rs
        |> edit(
          &(&1["kind"] == "tx_economics" and &1["tx"] == happy),
          &put_in(&1, ["data", "provider"], "second")
        )
        |> drop(fn r -> r["tx"] == Enum.at(f.ids, 3) end)
      end)

    d = Compare.diff(f.day, other)
    assert d.common == 4
    assert d.only_a == [Enum.at(f.ids, 3)]
    assert d.only_b == []

    assert %{tx: ^happy, field: :provider, a: "best", b: "second"} =
             Enum.find(d.divergences, &(&1.field == :provider))

    assert d.identical == 3

    reverse = Compare.diff(other, f.day)
    assert reverse.only_b == [Enum.at(f.ids, 3)]
  end

  # ---- reports -----------------------------------------------------------

  test "reports render for people", %{fixture: f} do
    assert Report.verify(Rules.verify(f.day)) =~ "ALL RULES HELD"

    broken =
      mutate(f, fn rs ->
        rs ++ [Enum.find(rs, &(&1["kind"] == "step_succeeded" and &1["data"]["op"] == "pay"))]
      end)

    text = Report.verify(Rules.verify(broken))
    assert text =~ "VIOLATION" and text =~ "[single_pay]"

    state = Report.state(Replay.state_at(f.day, {:seq, 1_000_000}))
    assert state =~ "transactions:" and state =~ "providers:" and state =~ "policy in force"

    {:ok, w} = WhatIf.run(f.day, %{"min_validity_ms" => 10_000_000})
    assert Report.what_if(w) =~ "would have had no option at all"
    {:ok, w2} = WhatIf.run(f.day, %{"weights" => %{"cost" => 1}})
    assert Report.what_if(w2) =~ "what if"

    other =
      mutate(f, fn rs ->
        edit(rs, &(&1["kind"] == "tx_economics"), &put_in(&1, ["data", "provider"], "zzz"))
      end)

    assert Report.diff(Compare.diff(f.day, other)) =~ "divergence"
  end
end

defmodule ActorTxSpike.ReplayIntegrationTest do
  @moduledoc """
  The whole point: run the live demo world with faults being injected by an
  operator, keep the audit log, then replay that day from the log alone.
  """
  use ExUnit.Case, async: false

  alias ActorTxSpike.{Audit, Demo, Events, Replay}
  alias ActorTxSpike.Replay.{Compare, Report, Rules, WhatIf}

  @fast [
    deadline_ms: 300,
    step_timeout_ms: 300,
    backoff_ms: 5,
    reconcile_interval_ms: 5,
    linger_ms: 60_000
  ]

  setup do
    prefix = "rp#{System.unique_integer([:positive])}"
    dir = Path.join(System.tmp_dir!(), "replay-int-#{System.unique_integer([:positive])}")
    path = Path.join(dir, "day.log")

    {:ok, audit} = Audit.start_link(path: path, name: :replay_int_audit)
    Process.unlink(audit)

    {:ok, demo} =
      Demo.start_link(timers: false, payment_opts: @fast, auto_interval_ms: 20, prefix: prefix)

    {:ok, _} = Demo.control(%{"action" => "pace", "factor" => 0.05})

    on_exit(fn ->
      stop_quietly(demo)
      stop_quietly(audit)
      File.rm_rf(dir)
    end)

    %{path: path, prefix: prefix, audit: audit}
  end

  defp ctl(cmd), do: Demo.control(cmd)

  defp submit(prefix, n, amount, extra \\ %{}) do
    id = "#{prefix}-#{n}"
    {:ok, ^id} = ctl(Map.merge(%{"action" => "submit", "amount" => amount}, extra))
    ActorTxSpike.await(id, 5_000)
    id
  end

  defp wait_logged(path, ids) do
    Enum.reduce_while(1..300, nil, fn _, _ ->
      logged =
        if File.exists?(path) do
          path
          |> File.stream!()
          |> Enum.map(&JSON.decode!/1)
          |> Enum.filter(&(&1["kind"] == "tx_costed"))
          |> Enum.map(& &1["tx"])
        else
          []
        end

      if Enum.all?(ids, &(&1 in logged)),
        do: {:halt, :ok},
        else: Process.sleep(20) && {:cont, nil}
    end)
  end

  test "a day with injected faults can be replayed, verified, queried and compared", %{
    path: path,
    prefix: prefix,
    audit: audit
  } do
    a = submit(prefix, 1, 1_000)
    ctl(%{"action" => "provider", "id" => "cobalt", "do" => "authorise_fails"})
    b = submit(prefix, 2, 2_000)
    ctl(%{"action" => "provider", "id" => "cobalt", "do" => "heal"})
    c = submit(prefix, 3, 500, %{"merchant" => "acme"})
    ids = [a, b, c]
    wait_logged(path, ids)
    GenServer.stop(audit)

    # the day loads (chain verified) and holds the conditions it ran under
    assert {:ok, day} = Replay.load(path)

    mine =
      Replay.from_records(
        Enum.filter(day.records, &(&1["tx"] in ids or &1["kind"] in ~w(control health)))
      )

    assert mine.tx_order == ids

    # every rule held, including through a failover caused by the injected fault
    result = Rules.verify(mine)
    assert result.ok?, inspect(result.violations)
    assert result.outcomes == %{completed: 3}

    assert "cobalt" in for(
             %{"kind" => "undone", "tx" => ^b, "data" => d} <- mine.records,
             do: d["provider"]
           )

    # the operator's actions are part of the record, in order
    actions =
      for %{"kind" => "control", "data" => %{"command" => cmd}} <- mine.records,
          cmd["action"] == "provider",
          do: cmd["do"]

    assert actions == ["authorise_fails", "heal"]

    # exact conditions mid-day: while the fault was on, cobalt was refusing authorise
    after_fault =
      Enum.find(
        mine.records,
        &(&1["kind"] == "control" and &1["data"]["command"]["do"] == "authorise_fails")
      )

    state = Replay.state_at(mine, {:n, after_fault["n"]})
    assert state.outcomes == %{completed: 1}
    assert List.last(state.controls).command["do"] == "authorise_fails"

    # strategy comparison on the real day
    assert {:ok, w} = WhatIf.run(mine, %{"weights" => %{"cost" => 1}, "max_bps_from_best" => nil})
    assert w.transactions == 3

    # a day matches itself, and diverges from a doctored copy
    assert %{identical: 3, divergences: []} = Compare.diff(mine, mine)

    # evidence an auditor can read, and rule breaches that are caught on a doctored day
    assert Report.verify(result) =~ "ALL RULES HELD"
    assert Report.state(state) =~ "in flight"
    assert Report.what_if(w) =~ "what if"
    assert [%{id: ^a}, %{id: ^b}, %{id: ^c}] = Replay.transactions(mine)
    assert Replay.tx(mine, b).provider != "cobalt"

    forgotten = Enum.reject(mine.records, &(&1["kind"] == "undone" and &1["tx"] == b))
    broken = Rules.verify(Replay.from_records(forgotten))
    refute broken.ok?
    assert Enum.any?(broken.violations, &(&1.rule == :failed_clean and &1.tx == b))
    assert Report.verify(broken) =~ "VIOLATION"

    doctored =
      Replay.from_records(
        Enum.map(mine.records, fn
          %{"kind" => "tx_economics", "tx" => ^a} = r ->
            put_in(r, ["data", "provider"], "elsewhere")

          r ->
            r
        end)
      )

    diff = Compare.diff(mine, doctored)
    assert diff.identical == 2
    assert Enum.all?(diff.divergences, &(&1.tx == a))
    assert Enum.any?(diff.divergences, &(&1.field == :provider and &1.b == "elsewhere"))
  end

  test "the demo resumes a transaction that was killed mid-flight, from its log alone", %{
    path: path,
    prefix: prefix,
    audit: audit
  } do
    # slow the world down so there is time to kill the transaction while it is in flight
    {:ok, _} = ctl(%{"action" => "pace", "factor" => 0.3})
    Events.subscribe()
    id = "#{prefix}-1"
    {:ok, ^id} = ctl(%{"action" => "submit", "amount" => 1_000, "region" => "IN"})

    kill_when_started(id, "authorise")
    :ok = Events.barrier()
    wait_gone(id)

    # a restart: the log is loaded, and the demo (the live world) resumes what was open
    {:ok, day} = Replay.load(path)
    mine = Replay.from_records(Enum.filter(day.records, &(&1["tx"] == id)))
    assert [%{status: :open, tx: ^id}] = ActorTxSpike.Recovery.open(mine)

    assert [{^id, :ok}] = Demo.recover([mine])
    outcome = ActorTxSpike.await(id, 10_000)

    assert match?({:ok, _}, outcome) or match?({:error, {:needs_review, _}}, outcome),
           inspect(outcome)

    # new transactions cannot collide with the recovered one
    assert {:ok, next_id} = ctl(%{"action" => "submit", "amount" => 500})
    assert next_id != id

    # the resumed record is in the log, chained after the crash, and every rule still holds
    wait_logged(path, [id])
    GenServer.stop(audit)
    {:ok, whole} = Replay.load(path)
    resumed = Replay.from_records(Enum.filter(whole.records, &(&1["tx"] == id)))
    assert Enum.any?(resumed.records, &(&1["kind"] == "tx_resumed"))
    assert Rules.verify(resumed).ok?
  end

  test "jurisdiction rules apply in the demo: a forward to IN and retail to CN are refused", %{
    prefix: prefix
  } do
    Events.subscribe()
    forward = "#{prefix}-1"
    retail = "#{prefix}-2"

    {:ok, ^forward} =
      ctl(%{"action" => "submit", "amount" => 100, "region" => "IN", "instrument" => "forward"})

    {:ok, ^retail} =
      ctl(%{"action" => "submit", "amount" => 100, "region" => "CN", "customer_type" => "retail"})

    assert {:error, {:not_permitted, ["forward is not permitted in IN"]}} =
             ActorTxSpike.await(forward, 5_000)

    assert {:error, {:not_permitted, ["retail customers are not permitted in CN"]}} =
             ActorTxSpike.await(retail, 5_000)

    # an allowed corridor uses only its eligible providers
    ok = "#{prefix}-3"
    {:ok, ^ok} = ctl(%{"action" => "submit", "amount" => 100, "region" => "CN"})
    assert {:ok, %{provider: :meridian}} = ActorTxSpike.await(ok, 5_000)
  end

  defp kill_when_started(id, op) do
    op = String.to_existing_atom(op)

    receive do
      {:tx_event, %{tx_id: ^id, kind: :step_started, data: %{op: ^op}}} ->
        [{pid, _}] = Registry.lookup(ActorTxSpike.Registry, {:payment, id})
        Process.exit(pid, :kill)

      {:tx_event, _} ->
        kill_when_started(id, Atom.to_string(op))
    after
      5_000 -> flunk("never reached #{op}")
    end
  end

  defp wait_gone(id) do
    Enum.reduce_while(1..300, nil, fn _, _ ->
      if ActorTxSpike.snapshot(id) == nil,
        do: {:halt, :ok},
        else: Process.sleep(10) && {:cont, nil}
    end)
  end

  # The process may already be gone (check-then-stop would race); stopping must not fail a test.
  defp stop_quietly(pid) do
    GenServer.stop(pid)
  catch
    :exit, _ -> :ok
  end
end

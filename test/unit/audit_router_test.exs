defmodule ActorTxSpike.AuditRouterTest do
  use ExUnit.Case, async: true

  alias ActorTxSpike.{Audit, Events.Event, Replay}
  alias ActorTxSpike.Audit.Router
  alias ActorTxSpike.Replay.Residency

  setup do
    dir = Path.join(System.tmp_dir!(), "router-#{System.unique_integer([:positive])}")
    on_exit(fn -> File.rm_rf(dir) end)
    name = :"router_#{System.unique_integer([:positive])}"
    %{dir: dir, name: name}
  end

  defp start(ctx, logs \\ ["IN", "US"]) do
    {:ok, pid} = Router.start_link(dir: ctx.dir, logs: logs, name: ctx.name, subscribe: false)
    Process.unlink(pid)
    pid
  end

  defp ev(n, tx, kind, data \\ %{}),
    do:
      {:tx_event,
       %Event{
         seq: n,
         at_ms: 1_000 + n,
         tx_id: tx,
         actor: :payment,
         kind: kind,
         message: "m",
         data: data
       }}

  defp received(n, tx, logs),
    do: ev(n, tx, :tx_received, %{request: %{amount: 100}, eligibility: %{logs: logs}})

  defp load(ctx, log) do
    {:ok, day} = Replay.load(Path.join(ctx.dir, log <> ".log"))
    day
  end

  test "each transaction's events go only to the logs its decision names", ctx do
    pid = start(ctx)

    for e <- [
          received(1, "t-in", ["IN"]),
          ev(2, "t-in", :step_started),
          received(3, "t-glob", ["GLOBAL"]),
          received(4, "t-multi", ["GLOBAL", "US"]),
          ev(5, "t-in", :tx_completed),
          ev(6, nil, :health),
          ev(7, "t-in", :tx_costed)
        ],
        do: send(pid, e)

    assert :ok = GenServer.call(pid, :flush)

    assert Map.keys(load(ctx, "IN").by_tx) == ["t-in"]

    assert Enum.map(load(ctx, "IN").records, & &1["kind"]) ==
             ~w(tx_received step_started tx_completed tx_costed)

    assert Map.keys(load(ctx, "US").by_tx) == ["t-multi"]
    assert load(ctx, "GLOBAL").tx_order |> Enum.sort() == ["t-glob", "t-multi"]
    # events with no transaction (health, operator actions) are global
    assert Enum.any?(load(ctx, "GLOBAL").records, &(&1["kind"] == "health"))

    # the closing event releases the routing entry
    refute Map.has_key?(:sys.get_state(pid).tx_logs, "t-in")
    assert Map.has_key?(:sys.get_state(pid).tx_logs, "t-multi")
  end

  test "a transaction the router never saw start goes to the global log", ctx do
    pid = start(ctx)
    send(pid, ev(1, "stray", :step_started))
    :ok = GenServer.call(pid, :flush)
    assert Map.keys(load(ctx, "GLOBAL").by_tx) == ["stray"]
  end

  test "a resumed transaction re-teaches the router where it belongs", ctx do
    pid = start(ctx)
    send(pid, ev(1, "back", :tx_resumed, %{eligibility: %{logs: ["US"]}}))
    send(pid, ev(2, "back", :step_started))
    :ok = GenServer.call(pid, :flush)
    assert Enum.map(load(ctx, "US").records, & &1["kind"]) == ~w(tx_resumed step_started)
  end

  test "a restarted router remembers where open transactions belong; finished ones are forgotten",
       ctx do
    first = start(ctx)

    for e <- [
          received(1, "open-in", ["IN"]),
          received(2, "done-in", ["IN"]),
          ev(3, "done-in", :tx_costed),
          received(4, "open-resumed", ["US"]),
          ev(6, "open-in", :step_started)
        ],
        do: send(first, e)

    :ok = GenServer.call(first, :flush)
    GenServer.stop(first)

    second = start(ctx)
    assert :sys.get_state(second).tx_logs == %{"open-in" => ["IN"], "open-resumed" => ["US"]}

    # the rest of an open local-only transaction does not leak into the global log
    send(second, ev(5, "open-in", :step_started))
    :ok = GenServer.call(second, :flush)

    assert Enum.map(load(ctx, "IN").by_tx["open-in"], & &1["kind"]) ==
             ~w(tx_received step_started step_started)

    refute Map.has_key?(load(ctx, "GLOBAL").by_tx, "open-in")
  end

  test "regional logs are separate chains with their own heads and paths", ctx do
    pid = start(ctx)
    send(pid, received(1, "a", ["IN"]))
    send(pid, received(2, "b", ["US"]))
    :ok = GenServer.call(pid, :flush)

    heads = Router.heads(ctx.name)
    assert %{"IN" => %{records: 1}, "US" => %{records: 1}, "GLOBAL" => %{records: 0}} = heads
    assert heads["IN"].head != heads["US"].head

    paths = Router.paths(ctx.name)
    assert Map.keys(paths) |> Enum.sort() == ["GLOBAL", "IN", "US"]
    for {_log, path} <- paths, do: assert({:ok, _} = Audit.verify(path))
  end

  test "residency is provable from the logs, and a leak or a gap is caught", ctx do
    pid = start(ctx)

    for e <- [
          received(1, "a", ["IN"]),
          ev(2, "a", :tx_completed),
          received(3, "b", ["GLOBAL", "US"])
        ],
        do: send(pid, e)

    :ok = GenServer.call(pid, :flush)
    days = Map.new(["IN", "US", "GLOBAL"], &{&1, load(ctx, &1)})
    assert %{ok?: true, transactions: 2, violations: []} = Residency.check(days)

    # a leak: "a" must stay in IN, but a copy turns up in GLOBAL
    leaked =
      Map.update!(days, "GLOBAL", fn day ->
        Replay.from_records(day.records ++ days["IN"].records)
      end)

    assert %{ok?: false, violations: [%{rule: :residency_leak, tx: "a", log: "GLOBAL"}]} =
             Residency.check(leaked)

    # a gap: "b" must be in US too, but US lost it
    gap = Map.put(days, "US", Replay.from_records([]))

    assert %{ok?: false, violations: [%{rule: :residency_missing, tx: "b", log: "US"}]} =
             Residency.check(gap)

    # a log with no recorded decision is judged by where it is
    bare = %{
      "X" =>
        Replay.from_records([%{"n" => 1, "tx" => "z", "kind" => "tx_received", "data" => %{}}])
    }

    assert %{ok?: true} = Residency.check(bare)
  end

  test "a decision naming a log the router does not have crashes it: nowhere lawful to write",
       ctx do
    pid = start(ctx, ["IN"])
    Process.flag(:trap_exit, true)
    ref = Process.monitor(pid)

    ExUnit.CaptureLog.capture_log(fn ->
      send(pid, received(1, "lost", ["BR"]))
      assert_receive {:DOWN, ^ref, :process, ^pid, {%RuntimeError{message: msg}, _}}, 1_000
      assert msg =~ ~s(no audit log named "BR")
    end)
  end

  test "it refuses to start when a regional log has been tampered with", ctx do
    pid = start(ctx)
    send(pid, received(1, "a", ["IN"]))
    :ok = GenServer.call(pid, :flush)
    GenServer.stop(pid)

    File.write!(
      Path.join(ctx.dir, "IN.log"),
      String.replace(File.read!(Path.join(ctx.dir, "IN.log")), "100", "1")
    )

    Process.flag(:trap_exit, true)

    assert {:error, {:log_refused, "IN", {:audit_chain_broken, _}}} =
             Router.start_link(
               dir: ctx.dir,
               logs: ["IN"],
               name: :"again_#{System.unique_integer([:positive])}",
               subscribe: false
             )
  end

  test "if a regional log dies the router stops with it", ctx do
    pid = start(ctx)
    Process.flag(:trap_exit, true)
    ref = Process.monitor(pid)
    {:ok, in_log} = Map.fetch(:sys.get_state(pid).audits, "IN")

    ExUnit.CaptureLog.capture_log(fn ->
      Process.exit(in_log, :kill)
      assert_receive {:DOWN, ^ref, :process, ^pid, {:log_down, "IN", :killed}}, 1_000
    end)
  end

  test "an exit from anything else is the router's own exit", ctx do
    pid = start(ctx)
    Process.flag(:trap_exit, true)
    ref = Process.monitor(pid)

    ExUnit.CaptureLog.capture_log(fn ->
      spawn(fn -> send(pid, {:EXIT, self(), :parent_gone}) end)
      assert_receive {:DOWN, ^ref, :process, ^pid, :parent_gone}, 1_000
    end)
  end

  test "as a durable sink it registers, flushes and unregisters when stopped normally", ctx do
    name = :"sink_router_#{System.unique_integer([:positive])}"
    {:ok, pid} = Router.start_link(dir: ctx.dir, logs: ["IN"], name: name)
    Process.unlink(pid)

    tx = "rsink-#{System.unique_integer([:positive])}"
    ActorTxSpike.Events.emit(tx, :payment, :tx_received, "m", %{eligibility: %{logs: ["IN"]}})
    assert :ok = ActorTxSpike.Events.barrier()
    assert File.read!(Path.join(ctx.dir, "IN.log")) =~ tx

    GenServer.stop(name)
    assert :ok = ActorTxSpike.Events.barrier()
  end

  test "a transaction in review keeps its routing until it is resolved, so a person's actions stay in its logs",
       ctx do
    router = start(ctx)

    for e <- [
          received(1, "rv-in", ["IN"]),
          ev(2, "rv-in", :needs_review),
          ev(3, "rv-in", :tx_costed),
          # a person acts later: this must still go to IN, not GLOBAL
          ev(4, "rv-in", :review_checked),
          received(5, "plain", ["IN"]),
          ev(6, "plain", :tx_costed)
        ],
        do: send(router, e)

    :ok = GenServer.call(router, :flush)
    state = :sys.get_state(router)
    assert state.tx_logs == %{"rv-in" => ["IN"]} and state.reviewing == MapSet.new(["rv-in"])

    assert Enum.map(load(ctx, "IN").by_tx["rv-in"], & &1["kind"]) ==
             ~w(tx_received needs_review tx_costed review_checked)

    refute Map.has_key?(load(ctx, "GLOBAL").by_tx, "rv-in")

    # resolving closes the entry (the resolution itself is still recorded in IN)
    send(router, ev(7, "rv-in", :review_resolved))
    :ok = GenServer.call(router, :flush)
    assert :sys.get_state(router).tx_logs == %{}
    assert :sys.get_state(router).reviewing == MapSet.new()
    assert List.last(load(ctx, "IN").by_tx["rv-in"])["kind"] == "review_resolved"
  end

  test "a restarted router rebuilds what is still in review from the logs", ctx do
    first = start(ctx)

    for e <- [
          received(1, "rv-open", ["IN"]),
          ev(2, "rv-open", :needs_review),
          ev(3, "rv-open", :tx_costed),
          received(4, "rv-done", ["US"]),
          ev(5, "rv-done", :needs_review),
          ev(6, "rv-done", :tx_costed),
          ev(7, "rv-done", :review_resolved)
        ],
        do: send(first, e)

    :ok = GenServer.call(first, :flush)
    GenServer.stop(first)

    second = start(ctx)
    state = :sys.get_state(second)
    assert state.tx_logs == %{"rv-open" => ["IN"]}
    assert state.reviewing == MapSet.new(["rv-open"])
  end
end

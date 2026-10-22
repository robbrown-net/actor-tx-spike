defmodule ActorTxSpike.LiveSystemTest do
  @moduledoc """
  System tests: boot the *real* system as a separate OS process (`mix demo`)
  and drive it only through its network surface (HTTP + server-sent events),
  exactly as a browser or another service would. No module is called directly.

  Run with `mix test.system`. Because the system under test is a separate
  process, line coverage is not measured here; instead
  `docs/quality/QUALITY_SNAPSHOT.md` tracks use-case scenario coverage.
  """
  use ExUnit.Case, async: false

  alias ActorTxSpike.Wait

  alias ActorTxSpike.Replay
  alias ActorTxSpike.Replay.Rules

  @moduletag :system
  @moduletag timeout: 120_000

  @providers ~w(northwind bluebird cobalt meridian zenith)

  setup_all do
    port = free_port()
    mix = System.find_executable("mix") || flunk("mix not found on PATH")
    audit = Path.join(System.tmp_dir!(), "system-audit-#{port}.log")
    File.rm(audit)

    os_port =
      Port.open({:spawn_executable, mix}, [
        :binary,
        :exit_status,
        :stderr_to_stdout,
        args: [
          "demo",
          "--port",
          Integer.to_string(port),
          "--quiet",
          "--pace",
          "0.05",
          "--sentinel-ms",
          "300",
          "--audit",
          audit
        ],
        cd: File.cwd!()
      ])

    # `mix` re-execs into the Erlang VM, so match on the unique port in its command line.
    on_exit(fn ->
      System.cmd("pkill", ["-f", "demo --port #{port}"])
      Process.sleep(200)
      File.rm(audit)
    end)

    wait_until_up(port, 60_000, os_port)

    {:ok, port: port, audit: audit}
  end

  setup %{port: port} do
    for p <- @providers, do: control(port, %{"action" => "provider", "id" => p, "do" => "heal"})
    control(port, %{"action" => "auto", "on" => false})
    :ok
  end

  test "boots and serves the dashboard and a five-provider snapshot", %{port: port} do
    assert {200, page} = http(port, "GET", "/")
    assert page =~ "Actor TX Monitor"

    assert {200, body} = http(port, "GET", "/api/snapshot")
    snapshot = JSON.decode!(body)
    assert Enum.sort(Enum.map(snapshot["providers"], & &1["id"])) == Enum.sort(@providers)
    assert snapshot["policy"]["max_bps_from_best"] == 100
  end

  test "UC-1 main scenario: a submitted transaction completes with a coherent story", %{
    port: port
  } do
    id = submit(port, 1_000)
    story = await_story(port, id, "tx_completed")
    kinds = Enum.map(story, & &1["kind"])

    assert hd(kinds) == "tx_received"

    # what it cost and what it saved is part of the story (and the snapshot)
    costed = await_story(port, id, "tx_costed")
    assert Enum.any?(costed, &(&1["kind"] == "tx_costed" and &1["message"] =~ "to run"))
    assert {200, snap} = http(port, "GET", "/api/snapshot")
    assert %{"economics" => %{"overall" => %{"completed" => c}}} = JSON.decode!(snap)
    assert c >= 1

    for k <-
          ~w(select_started quote_received selection_made provider_chosen step_succeeded tx_completed) do
      assert k in kinds, "missing #{k} in #{inspect(kinds)}"
    end

    steps = for e <- story, e["kind"] == "step_succeeded", do: e["data"]["op"]
    assert steps == ["accept_quote", "authorise", "pay", "settle"]
    # providers narrate their own side
    assert Enum.any?(story, &(&1["actor"] =~ "provider:" and &1["kind"] == "responded"))
  end

  test "UC-1 ext 7a: authorise rejected -> undone -> failed over -> still completes", %{
    port: port
  } do
    # make the best-priced provider reject authorisation
    control(port, %{"action" => "provider", "id" => "cobalt", "do" => "authorise_fails"})
    id = submit(port, 4_000)
    story = await_story(port, id, "tx_completed")
    kinds = Enum.map(story, & &1["kind"])

    assert "step_failed" in kinds
    assert "undone" in kinds
    assert "failover" in kinds
    done = Enum.find(story, &(&1["kind"] == "tx_completed"))
    refute done["message"] =~ "via cobalt"
  end

  test "UC-1 ext 8c: unresolvable payment stops in needs_review without failing over", %{
    port: port
  } do
    for p <- @providers,
        do: control(port, %{"action" => "provider", "id" => p, "do" => "pay_vanishes"})

    id = submit(port, 2_500)
    story = await_story(port, id, "needs_review")
    kinds = Enum.map(story, & &1["kind"])

    assert "in_doubt" in kinds
    assert "reconcile_pending" in kinds
    refute "failover" in kinds
    refute "tx_completed" in kinds
  end

  test "UC-1 ext 8b: a lost payment answer is reconciled and completes", %{port: port} do
    for p <- @providers,
        do: control(port, %{"action" => "provider", "id" => p, "do" => "pay_answer_lost"})

    id = submit(port, 800)
    story = await_story(port, id, "tx_completed")
    kinds = Enum.map(story, & &1["kind"])

    assert "in_doubt" in kinds
    assert "reconciled" in kinds
  end

  test "UC-3: a version drift is detected by the sentinel and quarantines the provider", %{
    port: port
  } do
    # drift is a *change* from a version we have seen: wait until the sentinel has recorded
    # bluebird's baseline, otherwise the drifted version would simply become the baseline
    wait_for(fn -> provider_field(port, "bluebird", "version") != nil end, 10_000)

    control(port, %{"action" => "provider", "id" => "bluebird", "do" => "drift"})
    wait_for(fn -> provider_state(port, "bluebird") == "quarantined" end, 10_000)

    # the quarantined provider is not called for new work
    id = submit(port, 1_200)
    story = await_story(port, id, "tx_completed")
    assert Enum.any?(story, &(&1["kind"] == "provider_skipped" and &1["message"] =~ "bluebird"))

    control(port, %{"action" => "provider", "id" => "bluebird", "do" => "release"})
    wait_for(fn -> provider_state(port, "bluebird") != "quarantined" end, 10_000)
  end

  test "UC-5: an invalid policy is rejected with the reason; a valid one is accepted", %{
    port: port
  } do
    assert {422, body} = post(port, %{"action" => "policy", "weights" => %{"cost" => -1}})
    assert JSON.decode!(body)["error"] =~ "weights"

    assert {200, _} =
             post(port, %{
               "action" => "policy",
               "weights" => %{"cost" => 1},
               "max_bps_from_best" => 100
             })

    assert {422, _} = post(port, %{"action" => "provider", "id" => "nobody", "do" => "heal"})
  end

  test "UC-2: automatic traffic keeps completing transactions", %{port: port} do
    control(port, %{"action" => "auto", "on" => true})
    {:ok, stream} = open_stream(port)

    completed =
      collect(
        stream,
        "",
        [],
        fn events -> Enum.count(events, &(&1["kind"] == "tx_completed")) >= 3 end,
        30_000
      )

    :gen_tcp.close(stream)
    control(port, %{"action" => "auto", "on" => false})

    assert Enum.count(completed, &(&1["kind"] == "tx_completed")) >= 3
  end

  test "bulk send: a seeded random batch is sent over HTTP and every transaction is accounted for",
       %{port: port} do
    %{"ok" => true} =
      control(port, %{
        "action" => "bulk",
        "do" => "start",
        "count" => 25,
        "interval_ms" => 0,
        "mode" => "random",
        "seed" => 5
      })

    wait_for(fn -> bulk(port)["status"] == "finished" end, 60_000)
    done = bulk(port)

    assert done["submitted"] == 25 and done["in_flight"] == 0
    assert done["completed"] + done["failed"] + done["needs_review"] == 25
    assert done["completed"] >= 1

    assert %{"ok" => false, "error" => error} =
             control(port, %{"action" => "bulk", "do" => "start", "count" => 0})

    assert error =~ "count"
    control(port, %{"action" => "bulk", "do" => "clear"})
  end

  test "back-pressure: with an admission limit the sender is told to wait, and every transaction still completes",
       %{port: port} do
    %{"ok" => true} = control(port, %{"action" => "admission", "max_in_flight" => 2})

    %{"ok" => true} =
      control(port, %{
        "action" => "bulk",
        "do" => "start",
        "count" => 10,
        "interval_ms" => 0,
        "fixed" => %{"instrument" => "spot", "customer_type" => "business"}
      })

    wait_for(fn -> bulk(port)["status"] == "finished" end, 60_000)
    done = bulk(port)
    control(port, %{"action" => "admission", "max_in_flight" => "none"})
    control(port, %{"action" => "bulk", "do" => "clear"})

    assert done["submitted"] == 10 and done["completed"] == 10
    assert done["throttled"] >= 1

    {200, snapshot} = http(port, "GET", "/api/snapshot")

    assert %{"admission" => %{"limit" => "infinity", "rejected" => rejected}} =
             JSON.decode!(snapshot)

    assert rejected >= 1
  end

  test "UC-4: a refused settlement waits for a person, who checks, repairs, retries and resolves it",
       %{port: port} do
    for p <- @providers,
        do: control(port, %{"action" => "provider", "id" => p, "do" => "settle_fails"})

    id = submit(port, 500)
    wait_for(fn -> Enum.any?(review(port)["entries"], &(&1["tx"] == id)) end, 30_000)

    %{"provider" => provider, "status" => "open", "done" => done} =
      Enum.find(review(port)["entries"], &(&1["tx"] == id))

    assert done == ["accept_quote", "authorise", "pay"]

    # a decision with no name or no reason is refused, and nothing changes
    assert {422, _} =
             post(port, %{
               "action" => "review",
               "do" => "resolve",
               "tx" => id,
               "outcome" => "paid",
               "by" => "",
               "note" => "x"
             })

    assert %{"ok" => true, "result" => answers} =
             control(port, %{"action" => "review", "do" => "check", "tx" => id, "by" => "robin"})

    assert length(answers) == 4

    control(port, %{"action" => "provider", "id" => provider, "do" => "heal"})

    assert %{"ok" => true, "result" => %{"result" => result}} =
             control(port, %{
               "action" => "review",
               "do" => "retry",
               "tx" => id,
               "op" => "settle",
               "by" => "robin"
             })

    assert result =~ ":ok"

    assert %{"ok" => true} =
             control(port, %{
               "action" => "review",
               "do" => "resolve",
               "tx" => id,
               "outcome" => "paid",
               "by" => "robin",
               "note" => "settled after repair"
             })

    resolved = Enum.find(review(port)["entries"], &(&1["tx"] == id))

    assert %{"status" => "resolved", "resolution" => %{"outcome" => "paid", "by" => "robin"}} =
             resolved

    assert Enum.map(resolved["actions"], & &1["kind"]) == ~w(check retry result)

    for p <- @providers, do: control(port, %{"action" => "provider", "id" => p, "do" => "heal"})
  end

  test "UC-5 cascade and standing guarantee: merchant policy and margin are narrated and ledgered",
       %{port: port} do
    %{"ok" => true, "result" => id} =
      control(port, %{
        "action" => "submit",
        "amount" => 1_000,
        "merchant" => "acme",
        "customer" => "vip-1"
      })

    story = await_story(port, id, "tx_completed")
    resolved = Enum.find(story, &(&1["kind"] == "policy_resolved"))
    assert resolved["message"] =~ "global + merchant:acme + customer:vip-1"
    assert Enum.any?(story, &(&1["kind"] in ["guarantee_margin", "guarantee_exposure"]))

    {200, body} = http(port, "GET", "/api/snapshot")
    ledger = JSON.decode!(body)["ledger"]
    assert Enum.any?(ledger, &(&1["merchant_id"] == "acme" and &1["count"] >= 1))

    # a merchant without the paid entitlement is ignored, and the story says so
    %{"result" => id2} =
      control(port, %{"action" => "submit", "amount" => 500, "merchant" => "globex"})

    story2 = await_story(port, id2, "tx_completed")

    assert Enum.find(story2, &(&1["kind"] == "policy_resolved"))["message"] =~
             "ignored: not_entitled"
  end

  test "deadline: a regional cut-off that has passed stops the transaction and undoes its work",
       %{port: port} do
    # a cut-off a moment away; with realistic provider latency the transaction crosses it
    control(port, %{"action" => "pace", "factor" => 1.0})
    control(port, %{"action" => "cutoff", "region" => "UK", "in_seconds" => 1.5})
    %{"result" => id} = control(port, %{"action" => "submit", "amount" => 750, "region" => "UK"})
    story = await_story(port, id, "tx_failed")
    control(port, %{"action" => "cutoff", "region" => "UK", "in_seconds" => 0})
    control(port, %{"action" => "pace", "factor" => 0.05})

    kinds = Enum.map(story, & &1["kind"])
    assert "deadline_set" in kinds
    assert "deadline_missed" in kinds
    refute "tx_completed" in kinds
  end

  # ---- helpers -----------------------------------------------------------

  test "the live day's audit log replays: chain intact, every rule held", %{
    port: port,
    audit: audit
  } do
    control(port, %{"action" => "provider", "id" => "cobalt", "do" => "authorise_fails"})
    id = submit(port, 1_500)
    await_story(port, id, "tx_costed")

    # the log is synced record by record, so it can be read while the system runs;
    # retry in case we catch a half-written last line
    day =
      Enum.find_value(1..50, fn _ ->
        case Replay.load(audit) do
          {:ok, day} -> if id in day.tx_order, do: day
          _ -> nil
        end || (Process.sleep(100) && nil)
      end)

    assert day, "the live audit log never became replayable"

    mine =
      Replay.from_records(Enum.filter(day.records, &(&1["tx"] == id or &1["kind"] == "control")))

    result = Rules.verify(mine)
    assert result.ok?, inspect(result.violations)
    assert %{completed: 1} = result.outcomes

    assert Enum.any?(
             mine.records,
             &(&1["kind"] == "control" and &1["data"]["command"]["do"] == "authorise_fails")
           )

    assert Enum.any?(mine.records, &(&1["kind"] == "undone"))
  end

  defp free_port do
    {:ok, l} = :gen_tcp.listen(0, [])
    {:ok, port} = :inet.port(l)
    :gen_tcp.close(l)
    port
  end

  defp wait_until_up(port, timeout, os_port) do
    deadline = System.monotonic_time(:millisecond) + timeout
    poll_up(port, deadline, os_port)
  end

  defp poll_up(port, deadline, os_port) do
    receive do
      {^os_port, {:exit_status, status}} -> flunk("mix demo exited early with #{status}")
    after
      250 -> :ok
    end

    case :gen_tcp.connect(~c"127.0.0.1", port, [], 200) do
      {:ok, socket} ->
        :gen_tcp.close(socket)

      {:error, _} ->
        if System.monotonic_time(:millisecond) > deadline, do: flunk("system did not start")
        poll_up(port, deadline, os_port)
    end
  end

  defp http(port, method, path, body \\ nil) do
    payload = if body, do: JSON.encode!(body), else: ""
    type = if body, do: "content-type: application/json\r\n", else: ""

    raw =
      "#{method} #{path} HTTP/1.1\r\nhost: x\r\n#{type}content-length: #{byte_size(payload)}\r\n\r\n#{payload}"

    {:ok, s} = :gen_tcp.connect(~c"127.0.0.1", port, [:binary, active: false])
    :ok = :gen_tcp.send(s, raw)
    resp = recv_all(s, "")
    :gen_tcp.close(s)
    [head, body] = String.split(resp, "\r\n\r\n", parts: 2)
    [_, status] = Regex.run(~r/HTTP\/1\.1 (\d+)/, head)
    {String.to_integer(status), body}
  end

  defp recv_all(s, acc) do
    case :gen_tcp.recv(s, 0, 1_500) do
      {:ok, d} -> recv_all(s, acc <> d)
      _ -> acc
    end
  end

  defp post(port, body), do: http(port, "POST", "/api/control", body)

  defp control(port, body) do
    {_status, resp} = post(port, body)
    JSON.decode!(resp)
  end

  defp submit(port, amount) do
    %{"ok" => true, "result" => id} = control(port, %{"action" => "submit", "amount" => amount})
    id
  end

  defp review(port) do
    {200, body} = http(port, "GET", "/api/review")
    JSON.decode!(body)
  end

  defp bulk(port) do
    {200, body} = http(port, "GET", "/api/bulk")
    JSON.decode!(body)
  end

  defp provider_state(port, id), do: provider_field(port, id, "state")

  defp provider_field(port, id, field) do
    {200, body} = http(port, "GET", "/api/snapshot")
    JSON.decode!(body)["providers"] |> Enum.find(&(&1["id"] == id)) |> Map.get(field)
  end

  defp wait_for(fun, timeout) do
    Wait.until!(fun,
      tries: div(timeout, 100),
      every_ms: 100,
      message: "condition not met in #{timeout}ms"
    )
  end

  defp open_stream(port) do
    {:ok, s} = :gen_tcp.connect(~c"127.0.0.1", port, [:binary, active: false])
    :ok = :gen_tcp.send(s, "GET /events HTTP/1.1\r\nhost: x\r\n\r\n")
    {:ok, s}
  end

  # Read the SSE stream until `done.(events)` is true; returns all decoded events.
  defp collect(socket, buffer, events, done, timeout) do
    deadline = System.monotonic_time(:millisecond) + timeout
    do_collect(socket, buffer, events, done, deadline)
  end

  defp do_collect(socket, buffer, events, done, deadline) do
    if System.monotonic_time(:millisecond) > deadline, do: flunk("stream condition not met")

    socket
    |> :gen_tcp.recv(0, 1_000)
    |> received(buffer, events, done)
    |> case do
      {:done, events} -> events
      {:more, buffer, events} -> do_collect(socket, buffer, events, done, deadline)
    end
  end

  defp received({:ok, data}, buffer, events, done) do
    {parsed, rest} = parse_sse(buffer <> data)
    events = events ++ parsed
    if done.(events), do: {:done, events}, else: {:more, rest, events}
  end

  defp received({:error, :timeout}, buffer, events, _done), do: {:more, buffer, events}

  defp received({:error, reason}, _buffer, _events, _done),
    do: flunk("stream closed: #{inspect(reason)}")

  defp parse_sse(text) do
    parts = String.split(text, "\n\n")
    {complete, [rest]} = Enum.split(parts, -1)

    events =
      for chunk <- complete,
          [_, json] <- [Regex.run(~r/data: (\{.*\})/s, chunk)],
          do: JSON.decode!(json)

    {events, rest}
  end

  defp await_story(port, tx_id, terminal_kind) do
    {:ok, s} = open_stream(port)

    events =
      collect(
        s,
        "",
        [],
        fn evs ->
          Enum.any?(evs, &(&1["tx"] == tx_id and &1["kind"] == terminal_kind))
        end,
        30_000
      )

    :gen_tcp.close(s)
    Enum.filter(events, &(&1["tx"] == tx_id))
  end
end

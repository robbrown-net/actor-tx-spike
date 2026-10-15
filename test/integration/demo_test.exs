defmodule ActorTxSpike.DemoIntegrationTest do
  # The demo world is a named singleton, so these tests cannot run concurrently.
  use ExUnit.Case, async: false

  alias ActorTxSpike.{Demo, Events}

  @fast [
    deadline_ms: 300,
    step_timeout_ms: 300,
    backoff_ms: 5,
    reconcile_interval_ms: 5,
    linger_ms: 60_000
  ]

  setup do
    # Unique prefix: payment processes linger, so ids must not repeat across tests.
    prefix = "d#{System.unique_integer([:positive])}"

    {:ok, pid} =
      Demo.start_link(timers: false, payment_opts: @fast, auto_interval_ms: 20, prefix: prefix)

    # Make the world quick: scale latencies right down.
    {:ok, _} = Demo.control(%{"action" => "pace", "factor" => 0.05})
    on_exit(fn -> if Process.alive?(pid), do: GenServer.stop(pid) end)
    {:ok, demo: pid, prefix: prefix}
  end

  defp ctl(cmd), do: Demo.control(cmd)

  defp provider(snapshot, id), do: Enum.find(snapshot.providers, &(&1.id == id))

  test "a submitted transaction completes and shows up everywhere", %{prefix: prefix} do
    Events.subscribe()
    id = prefix <> "-1"
    assert {:ok, ^id} = ctl(%{"action" => "submit", "amount" => "1000"})
    assert {:ok, %{provider: provider}} = ActorTxSpike.await(id, 5_000)
    assert provider in [:bluebird, :cobalt, :meridian, :northwind, :zenith]

    assert_receive {:tx_event, %{kind: :tx_completed, tx_id: ^id}}, 2_000
    snap = Demo.snapshot()
    assert snap.submitted == 1
    assert length(snap.providers) == 5
    assert %{state: :healthy} = provider(snap, provider)
  end

  test "operator presets change providers and the system routes around them" do
    for what <- ~w(slow stall error reject authorise_fails pay_answer_lost pay_vanishes) do
      assert {:ok, _} = ctl(%{"action" => "provider", "id" => "cobalt", "do" => what})
    end

    snap = Demo.snapshot()
    assert provider(snap, :cobalt).faults != []

    assert {:ok, _} = ctl(%{"action" => "provider", "id" => "cobalt", "do" => "heal"})
    assert provider(Demo.snapshot(), :cobalt).faults == []

    {:ok, _} = ctl(%{"action" => "provider", "id" => "cobalt", "do" => "reprice", "rate" => 1.2})
    assert provider(Demo.snapshot(), :cobalt).rate == 1.2

    # best-priced provider is rejecting at authorise: tx still completes elsewhere
    {:ok, _} =
      ctl(%{"action" => "provider", "id" => "cobalt", "do" => "reprice", "rate" => 1.0877})

    {:ok, _} = ctl(%{"action" => "provider", "id" => "cobalt", "do" => "authorise_fails"})
    {:ok, id} = ctl(%{"action" => "submit", "amount" => 500})
    assert {:ok, %{provider: provider}} = ActorTxSpike.await(id, 5_000)
    assert provider != :cobalt
  end

  test "bad commands are reported, not crashed on" do
    assert {:error, "unknown provider"} =
             ctl(%{"action" => "provider", "id" => "zzz", "do" => "heal"})

    assert {:error, "unknown action " <> _} =
             ctl(%{"action" => "provider", "id" => "cobalt", "do" => "dance"})

    assert {:error, "reprice needs a numeric rate"} =
             ctl(%{"action" => "provider", "id" => "cobalt", "do" => "reprice"})

    assert {:error, "unknown command"} = ctl(%{"action" => "nope"})
  end

  test "policy can be changed; invalid policy is rejected" do
    assert {:ok, _} =
             ctl(%{"action" => "policy", "weights" => %{"speed" => 1}, "max_bps_from_best" => 50})

    assert %{policy: %{"max_bps_from_best" => 50}} = Demo.snapshot()
    assert {:error, message} = ctl(%{"action" => "policy", "weights" => %{"cost" => -1}})
    assert message =~ "weights"
  end

  test "auto traffic submits on a timer and can be switched off" do
    assert {:ok, %{auto: true}} = ctl(%{"action" => "auto", "on" => true})
    Process.sleep(150)
    assert {:ok, %{auto: false}} = ctl(%{"action" => "auto", "on" => false})
    assert Demo.snapshot().submitted >= 2
    # a stray tick after switching off is ignored
    send(Process.whereis(Demo), :auto_tick)
    assert {:ok, %{auto: false}} = ctl(%{"action" => "auto", "on" => false})
  end

  test "sentinel probes and the market moves; drift quarantines until released" do
    demo = Process.whereis(Demo)
    send(demo, :market)
    send(demo, :sentinel)
    Process.sleep(60)
    assert provider(Demo.snapshot(), :bluebird).version == "1"

    {:ok, _} = ctl(%{"action" => "provider", "id" => "bluebird", "do" => "drift"})
    send(demo, :sentinel)
    Process.sleep(80)
    assert provider(Demo.snapshot(), :bluebird).state == :quarantined

    {:ok, _} = ctl(%{"action" => "provider", "id" => "bluebird", "do" => "release"})
    assert provider(Demo.snapshot(), :bluebird).state != :quarantined
  end

  test "handler delegates to the demo" do
    assert %{providers: [_ | _]} = Demo.Handler.snapshot()
    assert {:ok, _} = Demo.Handler.control(%{"action" => "pace", "factor" => 0.1})
  end
end

defmodule ActorTxSpike.DashboardIntegrationTest do
  @moduledoc "End to end over real HTTP: dashboard page, control API and live event stream."
  use ExUnit.Case, async: false

  alias ActorTxSpike.{Demo, Panel}

  @fast [
    deadline_ms: 300,
    step_timeout_ms: 300,
    backoff_ms: 5,
    reconcile_interval_ms: 5,
    linger_ms: 60_000
  ]

  defp http(port, raw) do
    {:ok, s} = :gen_tcp.connect(~c"127.0.0.1", port, [:binary, active: false])
    :ok = :gen_tcp.send(s, raw)
    out = recv(s, "")
    :gen_tcp.close(s)
    out
  end

  defp recv(s, acc) do
    case :gen_tcp.recv(s, 0, 1_000) do
      {:ok, d} -> recv(s, acc <> d)
      _ -> acc
    end
  end

  test "operate the system through the same HTTP API the browser uses" do
    prefix = "web#{System.unique_integer([:positive])}"
    {:ok, demo} = Demo.start_link(timers: false, payment_opts: @fast, prefix: prefix)
    {:ok, panel} = Panel.start_link(handler: Demo.Handler, port: 0)
    port = Panel.port(panel)
    on_exit(fn -> if Process.alive?(demo), do: GenServer.stop(demo) end)

    assert http(port, "GET / HTTP/1.1\r\nhost: x\r\n\r\n") =~ "Actor TX Monitor"

    {:ok, _} = Demo.control(%{"action" => "pace", "factor" => 0.05})
    body = ~s({"action":"submit","amount":750})

    resp =
      http(
        port,
        "POST /api/control HTTP/1.1\r\nhost: x\r\ncontent-type: application/json\r\ncontent-length: #{byte_size(body)}\r\n\r\n#{body}"
      )

    assert resp =~ ~s("result":"#{prefix}-1")

    assert {:ok, %{steps: [:accept_quote, :authorise, :pay]}} =
             ActorTxSpike.await("#{prefix}-1", 5_000)

    {:ok, s} = :gen_tcp.connect(~c"127.0.0.1", port, [:binary, active: false])
    :ok = :gen_tcp.send(s, "GET /events HTTP/1.1\r\nhost: x\r\n\r\n")
    stream = read_until(s, "", ~r/"kind":"tx_completed"[^\n]*"tx":"#{prefix}-1"/)
    :gen_tcp.close(s)
    assert stream =~ "provider:"
    assert stream =~ ~s("tx":"#{prefix}-1")

    assert http(port, "GET /api/snapshot HTTP/1.1\r\nhost: x\r\n\r\n") =~ ~s("submitted":1)

    # resource and application metrics are served too, and see the transaction
    ActorTxSpike.Metrics.sample()
    metrics = http(port, "GET /api/metrics HTTP/1.1\r\nhost: x\r\n\r\n")
    assert metrics =~ ~s("memory":{)
    assert metrics =~ ~s("sched":)
  end

  defp read_until(s, acc, pattern) do
    if Regex.match?(pattern, acc) do
      acc
    else
      {:ok, d} = :gen_tcp.recv(s, 0, 3_000)
      read_until(s, acc <> d, pattern)
    end
  end
end

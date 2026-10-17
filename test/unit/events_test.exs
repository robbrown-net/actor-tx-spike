defmodule ActorTxSpike.EventsTest do
  use ExUnit.Case, async: true

  alias ActorTxSpike.Events

  # Each test gets its own event server; `emit/5` talks to the named global,
  # so these tests drive the server directly.
  defp start(opts \\ []) do
    name = :"events_#{System.unique_integer([:positive])}"
    {:ok, pid} = Events.start_link([name: name] ++ opts)
    pid
  end

  defp emit(pid, n) do
    GenServer.cast(pid, {:emit, "tx", :actor, :kind, "event #{n}", %{n: n}})
  end

  test "events are sequenced, timestamped and delivered to subscribers" do
    pid = start()
    assert [] = GenServer.call(pid, {:subscribe, self()})
    emit(pid, 1)
    emit(pid, 2)

    assert_receive {:tx_event,
                    %Events.Event{seq: 1, message: "event 1", tx_id: "tx", data: %{n: 1}} = e}

    assert is_integer(e.at_ms)
    assert_receive {:tx_event, %{seq: 2}}
  end

  test "late subscribers get the buffered history; recent/1 limits" do
    pid = start()
    for n <- 1..5, do: emit(pid, n)

    assert [%{seq: 1} | _] = history = GenServer.call(pid, {:subscribe, self()})
    assert length(history) == 5
    assert [%{seq: 4}, %{seq: 5}] = GenServer.call(pid, {:recent, 2})
  end

  test "the buffer is bounded: oldest events are dropped" do
    pid = start(capacity: 3)
    for n <- 1..6, do: emit(pid, n)
    assert [%{seq: 4}, %{seq: 5}, %{seq: 6}] = GenServer.call(pid, {:recent, 10})
  end

  test "subscribing twice does not double-deliver; dead subscribers are dropped" do
    pid = start()
    GenServer.call(pid, {:subscribe, self()})
    GenServer.call(pid, {:subscribe, self()})
    emit(pid, 1)
    assert_receive {:tx_event, %{seq: 1}}
    refute_receive {:tx_event, %{seq: 1}}, 20

    {sub, ref} = spawn_monitor(fn -> GenServer.call(pid, {:subscribe, self()}) end)
    assert_receive {:DOWN, ^ref, :process, ^sub, _}
    emit(pid, 2)
    assert_receive {:tx_event, %{seq: 2}}
    assert %{subscribers: subs} = :sys.get_state(pid)
    assert map_size(subs) == 1
  end

  test "the global API emits with and without data, and recent/0 reads it back" do
    Events.subscribe()
    Events.emit("global-1", :me, :ping, "no data")
    Events.emit("global-1", :me, :ping, "with data", %{x: 1})

    assert_receive {:tx_event, %{tx_id: "global-1", message: "no data", data: %{}}}
    assert_receive {:tx_event, %{tx_id: "global-1", data: %{x: 1}}}
    assert Enum.any?(Events.recent(), &(&1.tx_id == "global-1"))
  end

  # ---- durable sinks and the write-ahead barrier --------------------------

  defmodule Sink do
    @moduledoc false
    use GenServer

    def start(mode), do: GenServer.start(__MODULE__, mode)
    def init(mode), do: {:ok, %{mode: mode, seen: []}}

    def handle_info({:tx_event, e}, s), do: {:noreply, %{s | seen: [e.seq | s.seen]}}

    def handle_call(:flush, _from, %{mode: :ok} = s), do: {:reply, :ok, s}
    # exits without answering, and without unregistering: a crash
    def handle_call(:flush, _from, %{mode: :crash} = s), do: {:stop, :boom, s}
    def handle_call(:seen, _from, s), do: {:reply, Enum.reverse(s.seen), s}
  end

  test "barrier with no sinks is immediate" do
    pid = start()
    assert :ok = Events.barrier(pid)
  end

  test "barrier returns only after every sink has handled earlier events" do
    pid = start()
    {:ok, sink} = Sink.start(:ok)
    [] = GenServer.call(pid, {:subscribe, sink})
    :ok = Events.register_sink(:s, sink, pid)

    for n <- 1..20, do: emit(pid, n)
    assert :ok = Events.barrier(pid)
    assert GenServer.call(sink, :seen) == Enum.to_list(1..20)
  end

  test "a sink that dies leaves the barrier failing until it is replaced" do
    pid = start()
    {:ok, dead} = Sink.start(:ok)
    :ok = Events.register_sink(:audit, dead, pid)
    Process.exit(dead, :kill)
    wait_until(fn -> match?({:error, {:sink_down, :audit}}, Events.barrier(pid)) end)

    {:ok, fresh} = Sink.start(:ok)
    :ok = Events.register_sink(:audit, fresh, pid)
    assert :ok = Events.barrier(pid)
  end

  test "a registered sink that crashes while flushing is a failure" do
    pid = start()
    {:ok, sink} = Sink.start(:crash)
    :ok = Events.register_sink(:flaky, sink, pid)

    ExUnit.CaptureLog.capture_log(fn ->
      assert {:error, {:sink_unresponsive, :flaky, _}} = Events.barrier(pid)
    end)
  end

  test "a sink that unregisters before stopping is not a failure" do
    pid = start()
    {:ok, sink} = Sink.start(:ok)
    :ok = Events.register_sink(:gone, sink, pid)
    Events.unregister_sink(:gone, pid)
    GenServer.stop(sink)
    assert :ok = Events.barrier(pid)
  end

  test "a sink that exits between listing and flushing, having unregistered, is skipped" do
    pid = start()
    {:ok, sink} = Sink.start(:crash)
    :ok = Events.register_sink(:leaving, sink, pid)
    # unregister arrives before the flush fails, as it does for a sink stopping on purpose
    Events.unregister_sink(:leaving, pid)

    ExUnit.CaptureLog.capture_log(fn -> assert :ok = Events.barrier(pid) end)
  end

  test "barrier reports an unavailable event server" do
    assert {:error, {:events_unavailable, _}} = Events.barrier(:no_such_events_server)
  end

  defp wait_until(fun, tries \\ 100) do
    cond do
      fun.() -> :ok
      tries == 0 -> flunk("condition not met")
      true -> Process.sleep(10) && wait_until(fun, tries - 1)
    end
  end
end

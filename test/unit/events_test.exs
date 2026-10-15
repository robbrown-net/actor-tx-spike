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
end

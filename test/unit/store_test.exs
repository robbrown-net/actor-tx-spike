defmodule ActorTxSpike.StoreTest do
  use ExUnit.Case, async: false

  alias ActorTxSpike.Store

  setup do
    Store.reset()
    # The store is persisted to disk and re-read on the next boot: never leave
    # partial snapshots behind for the next run.
    on_exit(fn -> Store.reset() end)
    :ok
  end

  test "put/get round-trips a snapshot" do
    snap = %{id: "s1", state: :queued}
    assert :ok = Store.put(snap)
    assert Store.get("s1") == snap
  end

  test "get returns nil for an unknown id" do
    assert Store.get("missing") == nil
  end

  test "inflight excludes terminal states" do
    Store.put(%{id: "q", state: :queued})
    Store.put(%{id: "c", state: :committed})
    Store.put(%{id: "f", state: :failed})

    assert [%{id: "q"}] = Store.inflight()
  end

  test "reset clears all entries" do
    Store.put(%{id: "x", state: :queued})
    assert :ok = Store.reset()
    assert Store.inflight() == []
  end
end

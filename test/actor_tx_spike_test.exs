defmodule ActorTxSpikeTest do
  use ExUnit.Case, async: false

  alias ActorTxSpike.{Store, Subsystem}

  setup do
    ActorTxSpike.TransactionSupervisor
    |> DynamicSupervisor.which_children()
    |> Enum.each(fn
      {_, pid, _, _} when is_pid(pid) ->
        DynamicSupervisor.terminate_child(ActorTxSpike.TransactionSupervisor, pid)

      _ ->
        :ok
    end)

    Store.reset()
    :ok
  end

  test "transaction walks queued → awaiting_a → awaiting_b → committed" do
    {:ok, id} = ActorTxSpike.submit(%{amount: 42})
    assert {:ok, snapshot} = ActorTxSpike.await(id)

    assert snapshot.state == :committed
    assert snapshot.result_a.subsystem == :a
    assert snapshot.result_b.subsystem == :b
    assert Store.get(id).state == :committed
  end

  test "crashing a subsystem mid-flight is retried until the transaction commits" do
    :ok = Subsystem.crash_next(:subsystem_a)

    {:ok, id} = ActorTxSpike.submit(%{amount: 7})
    assert {:ok, snapshot} = ActorTxSpike.await(id, 5_000)

    assert snapshot.state == :committed
    assert Process.whereis(:subsystem_a)
    assert Subsystem.processed?(:subsystem_a, id)
  end

  test "persisted in-flight work resumes after the transaction process dies" do
    {:ok, id} = ActorTxSpike.submit(%{amount: 99})
    assert {:ok, _} = ActorTxSpike.await(id)

    inflight = %{
      id: "resume-" <> id,
      payload: %{id: "resume-" <> id, amount: 3},
      state: :awaiting_b,
      retries: 0,
      max_retries: 5,
      result_a: %{subsystem: :a, tx_id: "resume-" <> id, echo: %{amount: 3}},
      result_b: nil,
      last_error: nil
    }

    :ok = Store.put(inflight)

    assert [{:ok, _pid}] = ActorTxSpike.restore_inflight()
    assert {:ok, snapshot} = ActorTxSpike.await(inflight.id)
    assert snapshot.state == :committed
    assert snapshot.result_b.subsystem == :b
    assert Store.get(inflight.id).state == :committed
  end
end

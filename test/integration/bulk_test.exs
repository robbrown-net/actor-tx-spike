defmodule ActorTxSpike.BulkIntegrationTest do
  @moduledoc """
  Sending many transactions into the real demo world: the batch runs, is counted
  exactly, can be stopped, repeats when seeded, and the jurisdiction rules still apply.
  """
  # The demo world and the bulk sender are named singletons.
  use ExUnit.Case, async: false

  alias ActorTxSpike.Wait

  alias ActorTxSpike.Demo
  alias ActorTxSpike.Demo.{Bulk, Handler}

  @fast [
    deadline_ms: 400,
    step_timeout_ms: 300,
    backoff_ms: 5,
    reconcile_interval_ms: 5,
    linger_ms: 60_000
  ]

  setup do
    prefix = "b#{System.unique_integer([:positive])}"
    {:ok, demo} = Demo.start_link(timers: false, payment_opts: @fast, prefix: prefix)
    {:ok, _} = Demo.control(%{"action" => "pace", "factor" => 0.05})
    {:ok, bulk} = Bulk.start_link()
    on_exit(fn -> for pid <- [bulk, demo], do: stop_quietly(pid) end)
    :ok
  end

  defp stop_quietly(pid) do
    GenServer.stop(pid)
  catch
    :exit, _ -> :ok
  end

  defp start!(params), do: assert({:ok, _} = Bulk.command(Map.put(params, "do", "start")))

  defp wait_status(status) do
    Wait.until!(fn -> Bulk.snapshot().status == status end,
      tries: 600,
      every_ms: 50,
      message: "the batch never became #{status}: #{inspect(Bulk.snapshot())}"
    )

    Bulk.snapshot()
  end

  defp ended(snap), do: snap.completed + snap.failed + snap.needs_review

  test "a batch sends exactly the number asked for and counts how each one ended" do
    start!(%{
      "count" => 12,
      "min_amount" => 10,
      "max_amount" => 20,
      "interval_ms" => 0,
      "fixed" => %{"merchant" => "acme", "region" => "UK"}
    })

    snap = wait_status(:finished)
    assert snap.submitted == 12 and snap.count == 12
    assert ended(snap) == 12 and snap.in_flight == 0 and snap.errors == 0
    assert snap.completed >= 1
    assert length(snap.recent) == 12
    assert Enum.all?(snap.recent, &(&1.amount >= 10.0 and &1.amount <= 20.0))
    assert snap.plan =~ "12 ×"
  end

  test "the pause between submissions is honoured" do
    start!(%{"count" => 5, "interval_ms" => 40, "max_amount" => 20, "min_amount" => 10})
    snap = wait_status(:finished)
    # four pauses of 40 ms at least
    assert snap.elapsed_ms >= 160
    assert snap.per_second < 25
  end

  test "random mode varies every setting, and the jurisdiction rules still apply" do
    start!(%{"count" => 40, "interval_ms" => 0, "mode" => "random", "seed" => 7})
    snap = wait_status(:finished)
    assert snap.submitted == 40 and ended(snap) == 40
    # some of the drawn combinations are not permitted (forward to IN, retail to CN)
    assert snap.failed >= 1
    assert snap.completed >= 1
  end

  test "a seeded random batch repeats exactly: the same transactions are sent" do
    params = %{"count" => 15, "interval_ms" => 0, "mode" => "random", "seed" => 99}

    start!(params)
    first = wait_status(:finished)
    assert {:ok, _} = Bulk.command(%{"do" => "clear"})
    start!(params)
    second = wait_status(:finished)

    amounts = fn snap -> Enum.map(snap.recent, & &1.amount) end
    assert amounts.(first) == amounts.(second)
    assert {first.completed, first.failed} == {second.completed, second.failed}
  end

  test "stopping ends the sending; what was already sent finishes normally" do
    start!(%{"count" => 500, "interval_ms" => 20})
    Wait.until!(fn -> Bulk.snapshot().submitted >= 3 end, tries: 200, every_ms: 20)
    assert {:ok, %{stopped: true}} = Bulk.command(%{"do" => "stop"})

    snap = Bulk.snapshot()
    assert snap.status == :stopped and snap.submitted < 500

    Wait.until!(fn -> Bulk.snapshot().in_flight == 0 end, tries: 400, every_ms: 50)
    final = Bulk.snapshot()
    assert ended(final) == final.submitted
    # no further transactions are sent after a stop
    Process.sleep(100)
    assert Bulk.snapshot().submitted == final.submitted
  end

  test "one batch at a time, bad plans are refused, and commands make sense when idle" do
    assert {:error, "no batch is running"} = Bulk.command(%{"do" => "stop"})
    assert {:error, message} = Bulk.command(%{"do" => "start", "count" => 0})
    assert message =~ "count"
    assert {:error, "unknown bulk command"} = Bulk.command(%{"do" => "dance"})
    assert %{status: :idle, available: true} = Bulk.snapshot()

    start!(%{"count" => 300, "interval_ms" => 50})
    assert {:error, refused} = Bulk.command(%{"do" => "start", "count" => 1})
    assert refused =~ "already running"
    assert {:error, "stop the batch first"} = Bulk.command(%{"do" => "clear"})
    assert {:ok, _} = Bulk.command(%{"do" => "stop"})
    assert {:ok, %{cleared: true}} = Bulk.command(%{"do" => "clear"})
    assert %{status: :idle} = Bulk.snapshot()
  end

  test "the dashboard handler routes bulk commands, and says so when bulk is not running" do
    assert {:ok, _} =
             Handler.control(%{
               "action" => "bulk",
               "do" => "start",
               "count" => 2,
               "interval_ms" => 0
             })

    snap = wait_status(:finished)
    assert snap.submitted == 2 and Handler.bulk().submitted == 2

    stop_quietly(Process.whereis(Bulk))
    assert %{available: false} = Handler.bulk()
    assert {:error, message} = Handler.control(%{"action" => "bulk", "do" => "stop"})
    assert message =~ "not running"
  end

  test "currencies travel with a submitted transaction; malformed ones are refused" do
    assert {:ok, id} =
             Demo.control(%{
               "action" => "submit",
               "amount" => 50,
               "source_currency" => "USD",
               "destination_currency" => "EUR"
             })

    assert {:ok, _} = ActorTxSpike.await(id, 10_000)

    assert Enum.any?(ActorTxSpike.snapshot(id).history, fn {_at, _phase, _kind, message} ->
             message =~ "(USD to EUR)"
           end)

    assert {:error, message} = Demo.control(%{"action" => "submit", "source_currency" => "usd"})
    assert message =~ "currency"
  end
end

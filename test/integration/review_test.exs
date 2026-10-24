defmodule ActorTxSpike.ReviewIntegrationTest do
  @moduledoc """
  The review workflow in the demo world, against regional audit logs: a settlement refused, a
  person checks, repairs, retries and resolves, and the whole of it lands in the transaction's
  own log and can be read back from the logs alone.
  """
  # The demo world, the review queue and the router are named singletons.
  use ExUnit.Case, async: false

  alias ActorTxSpike.Audit.Router
  alias ActorTxSpike.{Demo, Replay, Review, Wait}
  alias ActorTxSpike.Demo.Handler
  alias ActorTxSpike.Replay.{Residency, Rules}

  @fast [
    deadline_ms: 600,
    step_timeout_ms: 300,
    backoff_ms: 5,
    reconcile_interval_ms: 5
  ]

  setup do
    dir = Path.join(System.tmp_dir!(), "review-#{System.unique_integer([:positive])}")

    {:ok, sup} =
      Supervisor.start_link([{Router, dir: dir, logs: Demo.log_names()}], strategy: :one_for_one)

    Process.unlink(sup)
    prefix = "rv#{System.unique_integer([:positive])}"
    {:ok, demo} = Demo.start_link(timers: false, payment_opts: @fast, prefix: prefix)
    {:ok, _} = Demo.control(%{"action" => "pace", "factor" => 0.05})
    {:ok, review} = Review.start_link(providers: &Demo.review_providers/0)

    on_exit(fn ->
      for pid <- [review, demo], do: stop_quietly(pid)
      Supervisor.stop(sup)
      File.rm_rf(dir)
    end)

    %{dir: dir}
  end

  defp stop_quietly(pid) do
    GenServer.stop(pid)
  catch
    :exit, _ -> :ok
  end

  defp log(ctx, name), do: Path.join(ctx.dir, name <> ".log")

  defp wait_logged(ctx, name, tx, kind) do
    Wait.until!(
      fn ->
        File.exists?(log(ctx, name)) and
          log(ctx, name)
          |> File.stream!()
          |> Enum.any?(&(&1 =~ ~s("tx":"#{tx}") and &1 =~ ~s("kind":"#{kind}")))
      end,
      tries: 300,
      every_ms: 20
    )
  end

  # An India transaction whose settlement the provider refuses.
  defp settle_refused(extra \\ %{}) do
    for p <- ~w(northwind cobalt),
        do: Demo.control(%{"action" => "provider", "id" => p, "do" => "settle_fails"})

    {:ok, id} =
      Demo.control(
        Map.merge(
          %{"action" => "submit", "amount" => 100, "region" => "IN", "instrument" => "spot"},
          extra
        )
      )

    assert {:error, {:needs_review, _}} = ActorTxSpike.await(id, 10_000)
    Wait.until!(fn -> Enum.any?(Review.queue(), &(&1.tx == id)) end)
    id
  end

  test "a refused settlement goes to review; a person checks, repairs, retries and resolves it, all in the IN log",
       ctx do
    id = settle_refused()
    %{provider: provider} = Enum.find(Review.queue(), &(&1.tx == id))
    wait_logged(ctx, "IN", id, "tx_costed")

    assert {:ok, answers} =
             Handler.control(%{
               "action" => "review",
               "do" => "check",
               "tx" => id,
               "by" => "robin"
             })

    assert Enum.map(answers, & &1.op) == ~w(accept_quote authorise pay settle)

    # the provider is repaired, and settle is sent again under its own key
    Demo.control(%{"action" => "provider", "id" => provider, "do" => "heal"})

    assert {:ok, %{result: result}} =
             Handler.control(%{
               "action" => "review",
               "do" => "retry",
               "tx" => id,
               "op" => "settle",
               "by" => "robin"
             })

    assert result =~ ":ok"

    assert {:ok, %{outcome: "paid"}} =
             Handler.control(%{
               "action" => "review",
               "do" => "resolve",
               "tx" => id,
               "outcome" => "paid",
               "by" => "robin",
               "note" => "settled after the provider was repaired"
             })

    wait_logged(ctx, "IN", id, "review_resolved")

    # the whole trail is in the transaction's own log, nowhere else
    {:ok, days} = Replay.load_dir(ctx.dir)
    kinds = Enum.map(days["IN"].by_tx[id], & &1["kind"])
    assert ~w(review_checked review_retry review_result review_resolved) -- kinds == []
    refute Map.has_key?(days["GLOBAL"].by_tx, id)
    assert Residency.check(days).ok?

    # the rules hold, including that the resolution says who and why
    assert Rules.verify(days["IN"]).ok?

    assert [%{resolution: "paid"}] =
             Enum.filter(Replay.transactions(days["IN"]), &(&1.id == id))
  end

  test "the queue is rebuilt from the logs alone, with what people did", ctx do
    id = settle_refused()
    wait_logged(ctx, "IN", id, "tx_costed")
    {:ok, _} = Review.check(id, "robin")
    wait_logged(ctx, "IN", id, "review_checked")

    {:ok, _} =
      Review.resolve(id, "written_off", "robin", "provider unreachable; exposure accepted")

    wait_logged(ctx, "IN", id, "review_resolved")

    {:ok, days} = Replay.load_dir(ctx.dir)
    [entry] = Enum.filter(Review.Queue.from_records(days["IN"].records), &(&1.tx == id))

    assert %{status: :resolved, resolution: %{outcome: "written_off", by: "robin"}} = entry
    assert Enum.map(entry.actions, & &1.kind) == ["check"]
    assert entry.reason =~ "settle"
  end

  test "the dashboard handler offers the queue, and says so when it is not running" do
    id = settle_refused()
    assert %{available: true, open: open, entries: entries} = Handler.review()
    assert open >= 1 and Enum.any?(entries, &(&1.tx == id))

    stop_quietly(Process.whereis(Review))
    assert %{available: false} = Handler.review()

    assert {:error, message} =
             Handler.control(%{"action" => "review", "do" => "check", "tx" => id, "by" => "x"})

    assert message =~ "not running"
  end
end

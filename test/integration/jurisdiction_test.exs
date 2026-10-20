defmodule ActorTxSpike.JurisdictionIntegrationTest do
  @moduledoc """
  Jurisdiction end to end: rules decide who may carry a corridor, the router
  keeps each transaction's records only where they may live, and replay proves
  both from the logs.
  """
  use ExUnit.Case, async: true

  alias ActorTxSpike.Wait

  alias ActorTxSpike.Audit.Router
  alias ActorTxSpike.{Eligibility, Events, Policy, Recovery, Replay}
  alias ActorTxSpike.Provider.{Fake, Server}
  alias ActorTxSpike.Replay.{Residency, Rules}

  @fast [backoff_ms: 5, reconcile_interval_ms: 5, step_timeout_ms: 500, deadline_ms: 500]

  setup do
    dir = Path.join(System.tmp_dir!(), "juris-#{System.unique_integer([:positive])}")
    name = :"juris_router_#{System.unique_integer([:positive])}"
    {:ok, router} = Router.start_link(dir: dir, logs: ["IN", "CN"], name: name)
    Process.unlink(router)

    on_exit(fn ->
      stop_quietly(router)
      File.rm_rf(dir)
    end)

    {:ok, eligibility} =
      Eligibility.new(%{
        "regions" => %{
          "IN" => %{
            "instruments" => ["spot"],
            "providers" => %{"allow" => ["alpha"]},
            "logs" => ["IN"],
            "local_only" => true
          },
          "CN" => %{
            "customer_types" => ["business"],
            "providers" => %{"allow" => ["beta"]},
            "logs" => ["CN"],
            "local_only" => true
          }
        }
      })

    providers =
      for {id, rate} <- [alpha: 1.05, beta: 1.04, gamma: 1.10] do
        {:ok, fake} = Fake.start_link(id: id, rate: rate)
        {:ok, server} = Server.start_link(adapter: Fake, config: fake, id: id)
        %{id: id, server: server, fake: fake}
      end

    %{dir: dir, router: name, eligibility: eligibility, providers: providers}
  end

  defp run(ctx, suffix, request) do
    id = "jr-#{System.unique_integer([:positive])}-#{suffix}"
    {:ok, policy} = Policy.new(%{"weights" => %{"cost" => 1}})

    {:ok, ^id} =
      ActorTxSpike.submit(Map.merge(%{id: id, amount: 100_000}, request),
        providers: ctx.providers,
        policy: policy,
        eligibility: ctx.eligibility,
        opts: @fast
      )

    ActorTxSpike.await(id, 5_000)
    id
  end

  defp wait_logged(ctx, log, id, kind \\ "tx_costed") do
    path = Path.join(ctx.dir, log <> ".log")

    Wait.until!(fn -> logged?(path, id, kind) end,
      tries: 300,
      every_ms: 20,
      message: "#{id} never reached #{log}"
    )
  end

  defp logged?(path, id, kind) do
    File.exists?(path) and
      path |> File.stream!() |> Enum.any?(&(&1 =~ id and &1 =~ ~s("kind":"#{kind}")))
  end

  defp day(ctx, log, ids) do
    {:ok, day} = Replay.load(Path.join(ctx.dir, log <> ".log"))
    Replay.from_records(Enum.filter(day.records, &(&1["tx"] in ids)))
  end

  test "records stay in the region that requires it, providers are restricted, and replay proves both",
       ctx do
    # gamma is cheapest everywhere, but may not carry IN or CN
    in_tx = run(ctx, "in", %{destination_region: "IN"})
    cn_tx = run(ctx, "cn", %{destination_region: "CN"})
    open_tx = run(ctx, "open", %{destination_region: "UK"})
    forward_tx = run(ctx, "fwd", %{destination_region: "IN", instrument: :forward})
    retail_tx = run(ctx, "retail", %{destination_region: "CN", customer_type: :retail})

    # every transaction, failed or not, ends with its cost record in its region's log
    for {log, id} <- [
          {"IN", in_tx},
          {"CN", cn_tx},
          {"GLOBAL", open_tx},
          {"IN", forward_tx},
          {"CN", retail_tx}
        ],
        do: wait_logged(ctx, log, id)

    ids = [in_tx, cn_tx, open_tx, forward_tx, retail_tx]

    days = %{
      "IN" => day(ctx, "IN", ids),
      "CN" => day(ctx, "CN", ids),
      "GLOBAL" => day(ctx, "GLOBAL", ids)
    }

    # each transaction is only where its jurisdiction allows
    assert Enum.sort(days["IN"].tx_order) == Enum.sort([in_tx, forward_tx])
    assert Enum.sort(days["CN"].tx_order) == Enum.sort([cn_tx, retail_tx])
    assert days["GLOBAL"].tx_order == [open_tx]
    assert %{ok?: true, transactions: 5} = Residency.check(days)

    # providers were restricted by corridor, ahead of price
    assert Replay.tx(days["IN"], in_tx).provider == "alpha"
    assert Replay.tx(days["CN"], cn_tx).provider == "beta"
    assert Replay.tx(days["GLOBAL"], open_tx).provider == "gamma"

    # not-permitted transactions failed before any provider was asked
    assert Replay.tx(days["IN"], forward_tx).outcome == :failed
    assert Replay.tx(days["CN"], retail_tx).outcome == :failed
    assert days["IN"].by_tx[forward_tx] |> Enum.all?(&(&1["kind"] != "quote_observed"))

    # every rule held in every region, including eligibility
    for {_log, d} <- days, do: assert(Rules.verify(d).ok?)

    # and a copy of an IN transaction in the global log would be caught
    leaked =
      Map.put(
        days,
        "GLOBAL",
        Replay.from_records(days["GLOBAL"].records ++ days["IN"].by_tx[in_tx])
      )

    assert %{ok?: false, violations: [%{rule: :residency_leak, tx: ^in_tx}]} =
             Residency.check(leaked)

    # each regional log is its own verifiable chain
    for {_log, path} <- Router.paths(ctx.router),
        do: assert({:ok, _} = ActorTxSpike.Audit.verify(path))
  end

  test "a restart resumes an open transaction from its regional log, and it stays in that region",
       ctx do
    {:ok, policy} = Policy.new(%{"weights" => %{"cost" => 1}})
    id = "jr-restart-#{System.unique_integer([:positive])}"
    Events.subscribe()

    {:ok, ^id} =
      ActorTxSpike.submit(%{id: id, amount: 100_000, destination_region: "IN"},
        providers: ctx.providers,
        policy: policy,
        eligibility: ctx.eligibility,
        opts: @fast ++ [linger_ms: 1]
      )

    # the node dies as soon as authorise has succeeded
    kill_after(id, :authorise)
    :ok = Events.barrier()

    # restart: stop the router, start a fresh one on the same directory (chains are verified),
    # load every regional log, and recover
    GenServer.stop(ctx.router)
    name = :"juris_router_again_#{System.unique_integer([:positive])}"
    {:ok, again} = Router.start_link(dir: ctx.dir, logs: ["IN", "CN"], name: name)
    Process.unlink(again)

    days =
      for path <- Path.wildcard(Path.join(ctx.dir, "*.log")) do
        {:ok, day} = Replay.load(path)
        # the logs also hold other tests' traffic from the shared event stream
        Replay.from_records(Enum.filter(day.records, &(&1["tx"] == id)))
      end

    wait_gone(id)

    results =
      Recovery.recover(days, providers: ctx.providers, eligibility: ctx.eligibility, opts: @fast)

    assert results == [{id, :ok}]

    # Killed mid-flight, the pay call may or may not have reached the provider. Either
    # way the answer is safe: completed at the one eligible provider, or held for a human.
    outcome = ActorTxSpike.await(id, 5_000)

    assert match?({:ok, %{provider: :alpha}}, outcome) or
             match?({:error, {:needs_review, {:in_doubt, :pay}}}, outcome),
           inspect(outcome)

    for other <- Enum.reject(ctx.providers, &(&1.id == :alpha)),
        do: assert(Fake.effects(other.fake) == %{})

    wait_logged(%{ctx | router: name}, "IN", id)

    # the resumed records went to the region too; nothing leaked anywhere else
    reloaded =
      for log <- ["IN", "CN", "GLOBAL"], into: %{} do
        {:ok, day} = Replay.load(Path.join(ctx.dir, log <> ".log"))
        {log, Replay.from_records(Enum.filter(day.records, &(&1["tx"] == id)))}
      end

    assert Map.keys(reloaded["IN"].by_tx) == [id]
    assert reloaded["CN"].by_tx == %{} and reloaded["GLOBAL"].by_tx == %{}
    assert Enum.any?(reloaded["IN"].by_tx[id], &(&1["kind"] == "tx_resumed"))
    assert %{ok?: true} = Residency.check(reloaded)
    assert Rules.verify(reloaded["IN"]).ok?
    GenServer.stop(name)
  end

  defp kill_after(id, op) do
    receive do
      {:tx_event, %{tx_id: ^id, kind: :step_succeeded, data: %{op: ^op}}} ->
        [{pid, _}] = Registry.lookup(ActorTxSpike.Registry, {:payment, id})
        Process.exit(pid, :kill)

      {:tx_event, _} ->
        kill_after(id, op)
    after
      3_000 -> flunk("never reached #{op}")
    end
  end

  defp wait_gone(id) do
    Enum.reduce_while(1..200, nil, fn _, _ ->
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

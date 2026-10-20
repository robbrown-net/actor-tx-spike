defmodule ActorTxSpike.EvidenceIntegrationTest do
  @moduledoc """
  The audit trail end to end: real transactions through real providers leave a
  complete, ordered, hash-chained record that an auditor can verify, and any
  edit to it is detected.
  """
  use ExUnit.Case, async: true

  alias ActorTxSpike.Wait

  alias ActorTxSpike.{Audit, Policy}
  alias ActorTxSpike.Provider.{Fake, Server}

  @fast [backoff_ms: 5, reconcile_interval_ms: 5, step_timeout_ms: 500, deadline_ms: 500]

  setup do
    dir = Path.join(System.tmp_dir!(), "evidence-#{System.unique_integer([:positive])}")
    path = Path.join(dir, "audit.log")

    {:ok, audit} =
      Audit.start_link(path: path, name: :"evidence_#{System.unique_integer([:positive])}")

    Process.unlink(audit)

    on_exit(fn ->
      File.rm_rf(dir)
      stop_quietly(audit)
    end)

    %{path: path, audit: audit}
  end

  defp providers(fake_opts) do
    for {id, opts} <- fake_opts do
      {:ok, fake} = Fake.start_link([id: id] ++ opts)
      {:ok, server} = Server.start_link(adapter: Fake, config: fake, id: id)
      %{id: id, fake: fake, server: server}
    end
  end

  defp run(providers, id, request) do
    {:ok, policy} = Policy.new(%{"weights" => %{"cost" => 1}})

    {:ok, ^id} =
      ActorTxSpike.submit(Map.put(request, :id, id),
        providers: Enum.map(providers, &%{id: &1.id, server: &1.server}),
        policy: policy,
        opts: @fast
      )

    ActorTxSpike.await(id, 5_000)
  end

  defp records(path, tx) do
    path
    |> File.stream!()
    |> Enum.map(&JSON.decode!/1)
    |> Enum.filter(&(&1["tx"] == tx))
  end

  defp recorded?(path, tx, kind),
    do: File.exists?(path) and Enum.any?(records(path, tx), &(&1["kind"] == kind))

  defp wait_for(path, tx, kind) do
    Wait.until!(fn -> recorded?(path, tx, kind) end,
      tries: 100,
      every_ms: 20,
      message: "audit log never recorded #{kind} for #{tx}"
    )
  end

  test "a completed and a failed transaction both leave verifiable evidence", %{path: path} do
    tag = System.unique_integer([:positive])
    ok_id = "ev-ok-#{tag}"
    bad_id = "ev-bad-#{tag}"

    assert {:ok, _} = run(providers(a: [rate: 1.1], b: [rate: 1.0]), ok_id, %{amount: 100_000})

    refusing = providers(a: [rate: 1.1, accept_quote_mode: :reject])
    assert {:error, _} = run(refusing, bad_id, %{amount: 100_000})

    for {tx, kind} <- [{ok_id, "tx_costed"}, {bad_id, "tx_costed"}], do: wait_for(path, tx, kind)

    ok_kinds = path |> records(ok_id) |> Enum.map(& &1["kind"])
    assert hd(ok_kinds) == "tx_received"
    assert "tx_completed" in ok_kinds and "tx_economics" in ok_kinds

    assert Enum.find_index(ok_kinds, &(&1 == "tx_completed")) <
             Enum.find_index(ok_kinds, &(&1 == "tx_economics"))

    bad_kinds = path |> records(bad_id) |> Enum.map(& &1["kind"])
    assert "tx_failed" in bad_kinds

    economics = path |> records(ok_id) |> Enum.find(&(&1["kind"] == "tx_economics"))

    assert %{"outcome" => "completed", "usage" => %{"provider_calls" => calls}} =
             economics["data"]

    assert calls > 0

    assert {:ok, %{records: n}} = Audit.verify(path)
    assert n >= length(ok_kinds) + length(bad_kinds)
  end

  test "an edit to the evidence is detected, wherever it is made", %{path: path, audit: audit} do
    id = "ev-tamper-#{System.unique_integer([:positive])}"
    assert {:ok, _} = run(providers(a: [rate: 1.1]), id, %{amount: 50_000})
    wait_for(path, id, "tx_costed")

    # stop appending (other tests share the global event stream) before we tamper
    GenServer.stop(audit)
    assert {:ok, _} = Audit.verify(path)

    original = File.read!(path)

    File.write!(
      path,
      String.replace(original, ~s("amount":50000), ~s("amount":5000), global: false)
    )

    assert {:error, {:line, _, :hash_mismatch}} = Audit.verify(path)

    File.write!(path, original)
    assert {:ok, _} = Audit.verify(path)
  end

  # The process may already be gone (check-then-stop would race); stopping must not fail a test.
  defp stop_quietly(pid) do
    GenServer.stop(pid)
  catch
    :exit, _ -> :ok
  end
end

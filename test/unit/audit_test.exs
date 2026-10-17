defmodule ActorTxSpike.AuditTest do
  use ExUnit.Case, async: true

  alias ActorTxSpike.{Audit, Events}

  setup do
    dir = Path.join(System.tmp_dir!(), "audit-#{System.unique_integer([:positive])}")
    path = Path.join(dir, "audit.log")
    on_exit(fn -> File.rm_rf(dir) end)
    %{path: path}
  end

  defp start(path, extra \\ []) do
    name = :"audit_#{System.unique_integer([:positive])}"
    {:ok, pid} = Audit.start_link([path: path, name: name, subscribe: false] ++ extra)
    name |> tap(fn _ -> Process.unlink(pid) end)
  end

  defp event(n, message \\ "something happened", data \\ %{}) do
    %Events.Event{
      seq: n,
      at_ms: 1_700_000_000_000 + n,
      tx_id: "tx-1",
      actor: :payment,
      kind: :step_succeeded,
      message: message,
      data: data
    }
  end

  defp fill(audit, count), do: for(n <- 1..count, do: :ok = Audit.record(audit, event(n)))

  test "records form a chain that verifies, and head reports it", %{path: path} do
    audit = start(path)
    fill(audit, 5)
    assert %{records: 5, head: head} = Audit.head(audit)
    assert {:ok, %{records: 5, head: ^head}} = Audit.verify(path)
    assert String.length(head) == 64
  end

  test "a missing file verifies as empty", %{path: path} do
    assert {:ok, %{records: 0, head: head}} = Audit.verify(path)
    assert head == String.duplicate("0", 64)
  end

  test "editing a record is detected at that line", %{path: path} do
    fill(start(path), 4)
    lines = File.read!(path) |> String.split("\n", trim: true)

    edited =
      List.update_at(lines, 1, &String.replace(&1, "something happened", "nothing happened"))

    File.write!(path, Enum.join(edited, "\n") <> "\n")
    assert {:error, {:line, 2, :hash_mismatch}} = Audit.verify(path)
  end

  test "removing or reordering records is detected", %{path: path} do
    fill(start(path), 4)
    [l1, l2, l3, l4] = File.read!(path) |> String.split("\n", trim: true)

    File.write!(path, Enum.join([l1, l3, l4], "\n") <> "\n")
    assert {:error, {:line, 2, :out_of_sequence}} = Audit.verify(path)

    File.write!(path, Enum.join([l1, l2, l2, l4], "\n") <> "\n")
    assert {:error, {:line, 3, :out_of_sequence}} = Audit.verify(path)
  end

  test "a record whose link to its predecessor is wrong is detected", %{path: path} do
    audit = start(path)
    fill(audit, 2)
    [l1, l2] = File.read!(path) |> String.split("\n", trim: true)
    forged = l2 |> JSON.decode!() |> Map.put("prev", String.duplicate("a", 64)) |> JSON.encode!()
    File.write!(path, Enum.join([l1, forged], "\n") <> "\n")
    assert {:error, {:line, 2, :chain_broken}} = Audit.verify(path)
  end

  test "garbage and records without a hash are malformed", %{path: path} do
    File.mkdir_p!(Path.dirname(path))
    File.write!(path, "not json\n")
    assert {:error, {:line, 1, :malformed}} = Audit.verify(path)
    File.write!(path, ~s({"n":1}\n))
    assert {:error, {:line, 1, :malformed}} = Audit.verify(path)
  end

  test "the chain resumes across a restart", %{path: path} do
    first = start(path)
    fill(first, 3)
    GenServer.stop(first)

    second = start(path)
    :ok = Audit.record(second, event(4))
    assert %{records: 4} = Audit.head(second)
    assert {:ok, %{records: 4}} = Audit.verify(path)
  end

  test "it refuses to start on top of a tampered log", %{path: path} do
    fill(start(path), 3)
    File.write!(path, String.replace(File.read!(path), "something happened", "x"))
    Process.flag(:trap_exit, true)

    assert {:error, {:audit_chain_broken, {:line, 1, :hash_mismatch}}} =
             Audit.start_link(path: path, name: :refuser, subscribe: false)
  end

  test "structured data is made JSON-safe and sync can be turned off", %{path: path} do
    audit = start(path, sync: false)
    :ok = Audit.record(audit, event(1, "m", %{pid: self(), pair: {:a, 1}, nested: %{k: [1, 2]}}))
    assert {:ok, %{records: 1}} = Audit.verify(path)
    assert File.read!(path) =~ ~s("kind":"step_succeeded")
  end

  test "card numbers are redacted, other digit runs are left alone" do
    assert Audit.scrub("paid with 4111 1111 1111 1111 ok") == "paid with [REDACTED-PAN] ok"
    assert Audit.scrub("card 5500-0000-0000-0004.") == "card [REDACTED-PAN]."
    assert Audit.scrub("order 1234567890123456 ref") == "order 1234567890123456 ref"
    assert Audit.scrub("amount 1000 minor units") == "amount 1000 minor units"
    assert Audit.scrub(%{a: ["378282246310005"], b: 7}) == %{a: ["[REDACTED-PAN]"], b: 7}
    assert Audit.scrub(nil) == nil
  end

  test "scrubbing happens before anything reaches disk", %{path: path} do
    audit = start(path)
    :ok = Audit.record(audit, event(1, "card 4111111111111111", %{note: "4012888888881881"}))
    refute File.read!(path) =~ "4111111111111111"
    refute File.read!(path) =~ "4012888888881881"
    assert {:ok, %{records: 1}} = Audit.verify(path)
  end

  test "it follows the live event stream when subscribed", %{path: path} do
    {:ok, pid} = Audit.start_link(path: path, name: :live_audit)
    Process.unlink(pid)
    Events.emit("audit-live-1", :payment, :tx_received, "live", %{})
    assert eventually(fn -> Audit.head(:live_audit).records >= 1 end)
    assert {:ok, %{records: n}} = Audit.verify(path)
    assert n >= 1
  end

  defp eventually(fun, tries \\ 100) do
    cond do
      fun.() -> true
      tries == 0 -> false
      true -> Process.sleep(20) && eventually(fun, tries - 1)
    end
  end
end

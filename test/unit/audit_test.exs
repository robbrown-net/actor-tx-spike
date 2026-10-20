defmodule ActorTxSpike.AuditTest do
  use ExUnit.Case, async: true

  alias ActorTxSpike.{Audit, Events, Poll}

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
    audit = start(path)
    fill(audit, 3)
    GenServer.stop(audit)
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

  defp eventually(fun), do: Poll.until(fun, tries: 100, every_ms: 20)

  test "a subscribed audit is a durable sink: the barrier waits for it, and stopping removes it",
       %{path: path} do
    name = :"sink_audit_#{System.unique_integer([:positive])}"
    {:ok, pid} = Audit.start_link(path: path, name: name)
    Process.unlink(pid)

    tx = "sink-#{System.unique_integer([:positive])}"
    Events.emit(tx, :payment, :tx_received, "before the barrier", %{})
    assert :ok = Events.barrier()

    # everything emitted before the barrier is already on disk
    assert path |> File.read!() |> String.contains?(tx)

    for reason <- [:normal, :shutdown] do
      GenServer.stop(name, reason)
      assert :ok = Events.barrier()
      {:ok, again} = Audit.start_link(path: path, name: name)
      Process.unlink(again)
    end

    GenServer.stop(name)
  end

  # ---- one writer per log -------------------------------------------------

  test "a second writer on a live log is refused, and the chain stays intact", %{path: path} do
    first = start(path)
    fill(first, 2)
    Process.flag(:trap_exit, true)

    assert {:error, {:audit_locked, owner}} =
             Audit.start_link(path: path, name: :intruder, subscribe: false)

    assert owner =~ "#{:os.getpid()} <"
    fill(first, 1)
    assert {:ok, %{records: 3}} = Audit.verify(path)
  end

  test "stopping the writer releases the log for the next one", %{path: path} do
    first = start(path)
    fill(first, 1)
    GenServer.stop(first)
    refute File.exists?(path <> ".lock")
    second = start(path)
    fill(second, 1)
    assert {:ok, %{records: 2}} = Audit.verify(path)
  end

  test "a lock left by a writer that died is taken over", %{path: path} do
    first = start(path)
    fill(first, 1)
    # a crash never runs terminate, so the lock stays behind
    pid = Process.whereis(first)
    Process.exit(pid, :kill)
    wait_dead(pid)
    assert File.exists?(path <> ".lock")

    second = start(path)
    fill(second, 1)
    assert {:ok, %{records: 2}} = Audit.verify(path)
  end

  test "a lock left by an OS process that no longer exists is taken over", %{path: path} do
    File.mkdir_p!(Path.dirname(path))
    File.write!(path <> ".lock", "999999 <0.1.0>")
    second = start(path)
    fill(second, 1)
    assert {:ok, %{records: 1}} = Audit.verify(path)
  end

  test "a lock held by another running OS process refuses us", %{path: path} do
    port = Port.open({:spawn, "sleep 5"}, [])
    {:os_pid, other} = Port.info(port, :os_pid)
    File.mkdir_p!(Path.dirname(path))
    File.write!(path <> ".lock", "#{other} <0.1.0>")
    Process.flag(:trap_exit, true)

    assert {:error, {:audit_locked, owner}} =
             Audit.start_link(path: path, name: :other, subscribe: false)

    assert owner =~ "#{other}"
    Port.close(port)
  end

  test "an unreadable lock file counts as stale", %{path: path} do
    File.mkdir_p!(Path.dirname(path))
    File.write!(path <> ".lock", "garbage")
    second = start(path)
    fill(second, 1)
    assert {:ok, %{records: 1}} = Audit.verify(path)
  end

  test "a log that fails verification does not keep the lock", %{path: path} do
    audit = start(path)
    fill(audit, 1)
    GenServer.stop(audit)
    File.write!(path, "not json\n")
    Process.flag(:trap_exit, true)

    assert {:error, {:audit_chain_broken, _}} =
             Audit.start_link(path: path, name: :bad, subscribe: false)

    refute File.exists?(path <> ".lock")
  end

  defp wait_dead(pid) do
    if Process.alive?(pid), do: Process.sleep(5) && wait_dead(pid), else: :ok
  end

  test "records are made durable together: on a flush, or shortly after on a timer", %{path: path} do
    audit = start(path)
    :ok = Audit.record(audit, event(1))
    assert :sys.get_state(audit).dirty

    # a flush (the write-ahead point) makes everything written so far durable
    assert :ok = GenServer.call(audit, :flush)
    refute :sys.get_state(audit).dirty
    assert :ok = GenServer.call(audit, :flush)

    # with no flush, the timer does it
    :ok = Audit.record(audit, event(2))
    assert :sys.get_state(audit).dirty
    Process.sleep(120)
    refute :sys.get_state(audit).dirty
    assert {:ok, %{records: 2}} = Audit.verify(path)
  end

  test "with sync off nothing is ever fsynced, but everything is still written", %{path: path} do
    audit = start(path, sync: false)
    :ok = Audit.record(audit, event(1))
    assert :ok = GenServer.call(audit, :flush)
    assert :sys.get_state(audit).dirty
    assert {:ok, %{records: 1}} = Audit.verify(path)
  end
end

defmodule ActorTxSpike.AuditSupervisedTest do
  # Not async: killing the router fails the *global* event barrier until it is back.
  use ExUnit.Case, async: false

  alias ActorTxSpike.{Application, Events, Replay}

  setup do
    dir = Path.join(System.tmp_dir!(), "supervised-#{System.unique_integer([:positive])}")
    name = :"supervised_router_#{System.unique_integer([:positive])}"
    on_exit(fn -> File.rm_rf(dir) end)
    %{dir: dir, name: name}
  end

  test "a router killed under supervision is restarted, re-takes its logs, and the system recovers",
       ctx do
    children = Application.children(audit: [dir: ctx.dir, logs: ["IN"], name: ctx.name])
    router_spec = Enum.find(children, &match?({ActorTxSpike.Audit.Router, _}, &1))
    {:ok, sup} = Supervisor.start_link([router_spec], strategy: :one_for_one)
    Process.unlink(sup)

    original = Process.whereis(ctx.name)
    tx = "sup-#{System.unique_integer([:positive])}"
    Events.emit(tx, :payment, :tx_received, "before", %{eligibility: %{logs: ["IN"]}})
    assert :ok = Events.barrier()

    # the writer dies without cleaning up: its lock files and sink registration are left behind
    Process.exit(original, :kill)
    wait_until(fn -> restarted?(ctx.name, original) end)
    wait_until(fn -> Events.barrier() == :ok end)

    Events.emit(tx, :payment, :step_started, "after", %{})
    assert :ok = Events.barrier()

    # one unbroken chain across the crash
    {:ok, days} = Replay.load_dir(ctx.dir)
    assert Map.keys(days) |> Enum.sort() == ["GLOBAL", "IN"]
    assert Enum.map(days["IN"].records, & &1["kind"]) == ["tx_received", "step_started"]

    Supervisor.stop(sup)
    assert :ok = Events.barrier()
  end

  test "an unreadable directory of logs is refused", ctx do
    File.mkdir_p!(ctx.dir)
    File.write!(Path.join(ctx.dir, "BAD.log"), "not json\n")
    assert {:error, {"BAD", {:integrity, _}}} = Replay.load_dir(ctx.dir)

    # even with verification off, a log that is not JSON is an error, not a crash
    assert {:error, {"BAD", :malformed_log}} = Replay.load_dir(ctx.dir, verify: false)
  end

  defp restarted?(name, original) do
    case Process.whereis(name) do
      nil -> false
      ^original -> false
      _new -> true
    end
  end

  defp wait_until(fun, tries \\ 300) do
    cond do
      fun.() -> :ok
      tries == 0 -> flunk("condition not met")
      true -> Process.sleep(10) && wait_until(fun, tries - 1)
    end
  end
end

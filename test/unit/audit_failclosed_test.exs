defmodule ActorTxSpike.AuditFailClosedTest do
  # Not async: a crashed audit makes the *global* event barrier fail until it is
  # replaced, which would starve every payment running in a parallel test.
  use ExUnit.Case, async: false

  alias ActorTxSpike.Wait

  alias ActorTxSpike.{Audit, Events}

  test "an audit that crashes leaves the system failing closed until it is replaced" do
    dir = Path.join(System.tmp_dir!(), "failclosed-#{System.unique_integer([:positive])}")
    path = Path.join(dir, "audit.log")
    on_exit(fn -> File.rm_rf(dir) end)

    name = :"crash_audit_#{System.unique_integer([:positive])}"
    {:ok, pid} = Audit.start_link(path: path, name: name)
    Process.unlink(pid)
    Process.exit(pid, :kill)
    refute_barrier_ok(name)

    # a replacement under the same name clears the failure
    {:ok, again} = Audit.start_link(path: path, name: name)
    Process.unlink(again)
    assert :ok = Events.barrier()
    GenServer.stop(name)
  end

  test "an audit that stops abnormally is not unregistered: it stays a failure until replaced" do
    dir = Path.join(System.tmp_dir!(), "failclosed-#{System.unique_integer([:positive])}")
    path = Path.join(dir, "audit.log")
    on_exit(fn -> File.rm_rf(dir) end)

    name = :"abnormal_audit_#{System.unique_integer([:positive])}"
    {:ok, pid} = Audit.start_link(path: path, name: name)
    Process.unlink(pid)

    ExUnit.CaptureLog.capture_log(fn -> GenServer.stop(name, :boom) end)
    refute_barrier_ok(name)

    {:ok, again} = Audit.start_link(path: path, name: name)
    Process.unlink(again)
    assert :ok = Events.barrier()
    GenServer.stop(name)
  end

  # The kill is delivered asynchronously; the barrier fails once the event server has seen it.
  defp refute_barrier_ok(name) do
    Wait.until!(fn -> Events.barrier() == {:error, {:sink_down, {:audit, name}}} end,
      tries: 100,
      every_ms: 10,
      message: "barrier never failed for #{inspect(name)}"
    )
  end
end

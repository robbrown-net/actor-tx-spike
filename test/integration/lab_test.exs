defmodule ActorTxSpike.LabIntegrationTest do
  @moduledoc """
  The Design for Test lab against the live demo world. The scenario library is the
  system-level regression suite for restarts, idempotency, jurisdiction routing and
  audit-log downtime: every scenario must pass.
  """
  # The lab kills the router and fails the audit log on purpose: that is global state.
  use ExUnit.Case, async: false

  @moduletag timeout: 180_000

  alias ActorTxSpike.Audit.Router
  alias ActorTxSpike.Demo
  alias ActorTxSpike.Demo.Lab

  @fast [
    deadline_ms: 600,
    step_timeout_ms: 400,
    backoff_ms: 5,
    reconcile_interval_ms: 5,
    reconcile_attempts: 3,
    linger_ms: 60_000
  ]

  setup do
    dir = Path.join(System.tmp_dir!(), "lab-#{System.unique_integer([:positive])}")

    {:ok, sup} =
      Supervisor.start_link([{Router, dir: dir, logs: Demo.log_names()}], strategy: :one_for_one)

    Process.unlink(sup)
    prefix = "lab#{System.unique_integer([:positive])}"

    {:ok, demo} = Demo.start_link(timers: false, payment_opts: @fast, prefix: prefix)
    {:ok, lab} = Lab.start_link(dir: dir, supervised: true)

    on_exit(fn ->
      for pid <- [lab, demo], do: stop_quietly(pid)
      Supervisor.stop(sup)
      File.rm_rf(dir)
    end)

    %{dir: dir}
  end

  defp wait_idle(tries \\ 1_200) do
    snapshot = Lab.snapshot()

    cond do
      snapshot.running == nil -> snapshot
      tries == 0 -> flunk("the scenario run never finished")
      true -> Process.sleep(100) && wait_idle(tries - 1)
    end
  end

  test "every scenario in the library passes" do
    assert {:ok, %{running: ["happy" | _]}} = Lab.command(%{"do" => "run", "id" => "all"})
    snapshot = wait_idle()

    failures =
      for s <- snapshot.scenarios, s.status != :pass do
        {s.id, s.status, for(c <- s[:checks] || [], not c.ok, do: {c.name, c.detail})}
      end

    assert failures == []
    assert length(snapshot.scenarios) >= 17

    # each scenario really judged something
    assert Enum.all?(snapshot.scenarios, &(length(&1.checks) >= 2))
  end

  defp stop_quietly(pid) do
    GenServer.stop(pid)
  catch
    :exit, _ -> :ok
  end
end

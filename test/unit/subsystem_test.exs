defmodule ActorTxSpike.SubsystemTest do
  use ExUnit.Case, async: false

  alias ActorTxSpike.Subsystem

  setup do
    name = :"unit_subsystem_#{System.unique_integer([:positive])}"
    start_supervised!({Subsystem, name: name, id: :unit})
    {:ok, name: name}
  end

  test "process returns an echo result tagged with the subsystem id", %{name: name} do
    assert {:ok, %{subsystem: :unit, tx_id: "t1", echo: %{n: 1}}} =
             Subsystem.process(name, "t1", %{n: 1})
  end

  test "process is idempotent per tx_id", %{name: name} do
    {:ok, first} = Subsystem.process(name, "t1", %{n: 1})
    assert {:ok, ^first} = Subsystem.process(name, "t1", %{n: 2})
  end

  test "processed? reflects whether a tx was seen", %{name: name} do
    refute Subsystem.processed?(name, "t1")
    {:ok, _} = Subsystem.process(name, "t1", %{})
    assert Subsystem.processed?(name, "t1")
  end

  test "crash_next kills the process on the next request and it is restarted", %{name: name} do
    :ok = Subsystem.crash_next(name)
    old = Process.whereis(name)
    ref = Process.monitor(old)

    assert catch_exit(Subsystem.process(name, "t1", %{}))
    assert_receive {:DOWN, ^ref, :process, ^old, _}

    new = wait_for_restart(name, old)
    assert is_pid(new) and new != old
    refute Subsystem.processed?(name, "t1")
  end

  defp wait_for_restart(name, old, tries \\ 50) do
    case Process.whereis(name) do
      pid when is_pid(pid) and pid != old -> pid
      _ when tries > 0 -> Process.sleep(10) && wait_for_restart(name, old, tries - 1)
      _ -> nil
    end
  end
end

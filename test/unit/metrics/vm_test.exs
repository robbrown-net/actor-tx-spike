defmodule ActorTxSpike.Metrics.VMTest do
  use ExUnit.Case, async: true

  alias ActorTxSpike.Metrics.VM

  test "memory reports every category as positive bytes" do
    m = VM.memory()
    for k <- [:total, :processes, :binary, :ets, :code, :atom, :system], do: assert(m[k] > 0)
  end

  test "scheduler accounting yields increasing wall time" do
    VM.enable_scheduler_accounting()
    {a1, t1} = VM.scheduler_wall_time()
    Process.sleep(20)
    {a2, t2} = VM.scheduler_wall_time()
    assert t2 > t1
    assert a2 >= a1
  end

  test "cpu, reductions, run queue, gc and counts are non-negative numbers" do
    assert VM.cpu_ms() >= 0
    assert VM.reductions() > 0
    assert VM.run_queue() >= 0
    assert VM.gc_count() > 0
    assert VM.process_count() > 0
    assert VM.port_count() >= 0
  end

  test "tcp_bytes counts a live socket's traffic" do
    {:ok, listen} = :gen_tcp.listen(0, [:binary, active: false])
    {:ok, port} = :inet.port(listen)
    {:ok, client} = :gen_tcp.connect(~c"127.0.0.1", port, [:binary, active: false])
    {:ok, server} = :gen_tcp.accept(listen)
    :ok = :gen_tcp.send(client, String.duplicate("x", 500))
    {:ok, _} = :gen_tcp.recv(server, 500)

    {recv, send, sockets} = VM.tcp_bytes()
    assert recv >= 500
    assert send >= 500
    assert sockets >= 2
    for s <- [client, server, listen], do: :gen_tcp.close(s)
  end

  test "top_processes returns labelled, memory-sorted entries" do
    Process.register(self(), :vm_test_proc)
    top = VM.top_processes(5)
    assert length(top) == 5
    assert top == Enum.sort_by(top, & &1.memory, :desc)
    assert Enum.all?(top, &(is_binary(&1.label) and is_integer(&1.mailbox)))

    all = VM.top_processes(100_000)
    assert Enum.any?(all, &(&1.label == ":vm_test_proc"))
    assert Enum.any?(all, &(&1.label =~ "."))
  end
end

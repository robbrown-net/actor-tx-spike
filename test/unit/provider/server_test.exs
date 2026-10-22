defmodule ActorTxSpike.Provider.ServerTest do
  use ExUnit.Case, async: true

  alias ActorTxSpike.Wait

  alias ActorTxSpike.Provider.{Fake, Health, Server}

  defmodule Misbehaving do
    @behaviour ActorTxSpike.Provider
    def request_quote(:raise, _), do: raise("boom")
    def request_quote(:exit, _), do: exit(:gone)
    def accept_quote(_, _, _), do: :timeout
    def authorise(_, _, _), do: :timeout
    def pay(_, _, _), do: :timeout
    def settle(_, _), do: :timeout
    def cancel_accept(_, _), do: :timeout
    def void_authorisation(_, _), do: :timeout
    def refund(_, _), do: :timeout
    def status(_, _, _), do: :timeout
    def probe(_), do: :timeout
  end

  defmodule Blocking do
    @behaviour ActorTxSpike.Provider
    def request_quote(notify, _) do
      send(notify, {:blocked, self()})

      receive do
        :go -> {:error, :released}
      end
    end

    def accept_quote(_, _, _), do: :timeout
    def authorise(_, _, _), do: :timeout
    def pay(_, _, _), do: :timeout
    def settle(_, _), do: :timeout
    def cancel_accept(_, _), do: :timeout
    def void_authorisation(_, _), do: :timeout
    def refund(_, _), do: :timeout
    def status(_, _, _), do: :timeout
    def probe(_), do: :timeout
  end

  defmodule BlockingAll do
    @moduledoc "Every operation blocks until told to go."
    @behaviour ActorTxSpike.Provider
    defp block(notify) do
      send(notify, {:blocked, self()})

      receive do
        :go -> {:error, :released}
      end
    end

    def request_quote(notify, _), do: block(notify)
    def accept_quote(notify, _, _), do: block(notify)
    def authorise(notify, _, _), do: block(notify)
    def pay(notify, _, _), do: block(notify)
    def settle(notify, _), do: block(notify)
    def cancel_accept(notify, _), do: block(notify)
    def void_authorisation(notify, _), do: block(notify)
    def refund(notify, _), do: block(notify)
    def status(notify, _, _), do: block(notify)
    def probe(notify), do: block(notify)
  end

  defp start(opts \\ []) do
    {:ok, fake} = Fake.start_link(id: :p)
    opts = Keyword.merge([adapter: Fake, config: fake], opts)
    {:ok, server} = Server.start_link(opts)
    {server, fake}
  end

  defp wait_until(fun, tries \\ 100), do: Wait.until!(fun, tries: tries, every_ms: 5)

  defp synced(server), do: Server.health(server)

  test "successful calls make the provider healthy and update stats" do
    {server, _fake} = start()
    assert Server.state(server) == :unknown
    assert {:ok, quote} = Server.request_quote(server, %{id: "r"})
    assert {:ok, _} = Server.invoke(server, :pay, [quote, "k"])
    assert {:ok, _} = Server.status(server, :pay, "k")
    assert %{samples: 3} = Server.health(server) |> Health.stats()
    assert Server.state(server) == :healthy
  end

  test "outcomes are classified into health samples" do
    {server, fake} = start(health: [failure_threshold: 99])
    {:ok, quote} = Server.request_quote(server, %{id: "r"})

    Fake.configure(fake, quote_mode: :reject)
    assert {:rejected, _} = Server.request_quote(server, %{id: "r"})
    Fake.configure(fake, quote_mode: :timeout)
    assert :timeout = Server.request_quote(server, %{id: "r"})
    Fake.configure(fake, pay_mode: :in_doubt)
    assert :in_doubt = Server.invoke(server, :pay, [quote, "k"])
    Fake.configure(fake, status_mode: :unknown)
    assert :unknown = Server.status(server, :pay, "k")
    Fake.configure(fake, status_mode: :ok)
    assert {:ok, _} = Server.status(server, :pay, "k")
    assert :not_found = Server.status(server, :pay, "missing")

    outcomes =
      server |> synced() |> Map.fetch!(:window) |> Enum.map(&elem(&1, 0)) |> Enum.reverse()

    assert outcomes == [:success, :rejected, :timeout, :timeout, :error, :success, :success]
  end

  test "circuit opens after repeated failures, denies, then recovers via a probe call" do
    {server, fake} = start(health: [failure_threshold: 2, cool_down_ms: 20])
    Fake.configure(fake, quote_mode: {:error, :down})

    assert {:error, :down} = Server.request_quote(server, %{id: "r"})
    assert {:error, :down} = Server.request_quote(server, %{id: "r"})
    wait_until(fn -> Server.state(server) == :stalled end)
    assert {:error, :circuit_open} = Server.request_quote(server, %{id: "r"})

    Fake.configure(fake, quote_mode: :ok)
    Process.sleep(30)
    assert {:ok, _} = Server.request_quote(server, %{id: "r"})
    wait_until(fn -> Server.state(server) == :degraded end)

    # gradual recovery: two more successes (the probe was the first)
    assert {:ok, _} = Server.request_quote(server, %{id: "r"})
    assert {:ok, _} = Server.request_quote(server, %{id: "r"})
    wait_until(fn -> Server.state(server) == :healthy end)
  end

  @tag :regression

  test "adapter exceptions and exits become errors and count as failures" do
    {:ok, server} = Server.start_link(adapter: Misbehaving, config: :raise)
    assert {:error, {:raised, %RuntimeError{}}} = Server.request_quote(server, %{id: "r"})

    {:ok, server2} = Server.start_link(adapter: Misbehaving, config: :exit)
    assert {:error, {:exit, :gone}} = Server.request_quote(server2, %{id: "r"})
    assert %{window: [{:error, _}]} = synced(server2)
  end

  @tag :regression

  test "an overdue adapter call returns :timeout, is recorded, and frees its slot" do
    {:ok, server} = Server.start_link(adapter: Blocking, config: self())
    assert :timeout = Server.request_quote(server, %{id: "r"}, 20)
    assert_receive {:blocked, worker}
    wait_until(fn -> not Process.alive?(worker) end)

    wait_until(fn -> Server.in_flight(server) == 0 end)
    assert [{:timeout, _}] = synced(server).window
  end

  test "the server-level call_timeout is the default" do
    {:ok, server} = Server.start_link(adapter: Blocking, config: self(), call_timeout: 20)
    assert :timeout = Server.request_quote(server, %{id: "r"})
  end

  test "bulkhead rejects when in-flight limit is reached" do
    {:ok, server} = Server.start_link(adapter: Blocking, config: self(), max_in_flight: 1)
    task = Task.async(fn -> Server.request_quote(server, %{id: "r"}) end)
    assert_receive {:blocked, worker}
    assert Server.in_flight(server) == 1

    assert {:error, :overloaded} = Server.request_quote(server, %{id: "r2"})

    send(worker, :go)
    assert {:error, :released} = Task.await(task)
    wait_until(fn -> Server.in_flight(server) == 0 end)
  end

  test "priority classes: quotes may use 60% of the slots, steps all of them, undo and status 150%" do
    {:ok, server} = Server.start_link(adapter: BlockingAll, config: self(), max_in_flight: 10)

    hold = fn op, args, n ->
      for _ <- 1..n, do: Task.async(fn -> Server.invoke(server, op, args) end)
    end

    quotes = hold.(:request_quote, [%{id: "r"}], 6)
    wait_until(fn -> Server.in_flight(server) == 6 end)
    assert {:error, :overloaded} = Server.request_quote(server, %{id: "more"})

    steps = hold.(:accept_quote, [:q, "k"], 4)
    wait_until(fn -> Server.in_flight(server) == 10 end)
    assert {:error, :overloaded} = Server.invoke(server, :pay, [:q, "k"])

    undos = hold.(:cancel_accept, ["k"], 5)
    wait_until(fn -> Server.in_flight(server) == 15 end)
    assert {:error, :overloaded} = Server.invoke(server, :refund, ["k"])
    assert {:error, :overloaded} = Server.status(server, :pay, "k")

    for _ <- 1..15 do
      assert_receive {:blocked, worker}
      send(worker, :go)
    end

    for task <- quotes ++ steps ++ undos, do: Task.await(task)
    wait_until(fn -> Server.in_flight(server) == 0 end)
  end

  @tag :regression

  test "a caller that dies mid-call releases its slot and probe slot" do
    {:ok, server} = Server.start_link(adapter: Blocking, config: self(), max_in_flight: 1)
    {pid, ref} = spawn_monitor(fn -> Server.request_quote(server, %{id: "r"}) end)
    assert_receive {:blocked, _worker}
    Process.exit(pid, :kill)
    assert_receive {:DOWN, ^ref, :process, ^pid, _}

    wait_until(fn -> Server.in_flight(server) == 0 end)
  end

  test "probe records the version; a change quarantines until released" do
    {server, fake} = start()
    assert {:ok, %{version: "1"}} = Server.probe(server)
    assert {:ok, %{version: "1"}} = Server.probe(server)
    assert Server.state(server) == :healthy

    Fake.configure(fake, version: "2")
    assert {:ok, %{version: "2"}} = Server.probe(server)
    assert Server.state(server) == :quarantined
    assert {:error, :quarantined} = Server.request_quote(server, %{id: "r"})

    assert :ok = Server.release(server)
    assert {:ok, _} = Server.request_quote(server, %{id: "r"})
  end

  test "a failed probe does not touch the version" do
    {server, fake} = start()
    Fake.configure(fake, probe_mode: :timeout)
    assert :timeout = Server.probe(server)
    assert %{version: nil} = synced(server)
  end
end

defmodule ActorTxSpike.Provider.ServerTest do
  use ExUnit.Case, async: true

  alias ActorTxSpike.Provider.{Fake, Server}

  defmodule Misbehaving do
    @behaviour ActorTxSpike.Provider
    def request_quote(:raise, _), do: raise("boom")
    def request_quote(:exit, _), do: exit(:gone)
    def accept_quote(_, _, _), do: :timeout
    def authorise(_, _, _), do: :timeout
    def pay(_, _, _), do: :timeout
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
    def cancel_accept(_, _), do: :timeout
    def void_authorisation(_, _), do: :timeout
    def refund(_, _), do: :timeout
    def status(_, _, _), do: :timeout
    def probe(_), do: :timeout
  end

  defp start(opts \\ []) do
    {:ok, fake} = Fake.start_link(id: :p)
    opts = Keyword.merge([adapter: Fake, config: fake], opts)
    {:ok, server} = Server.start_link(opts)
    {server, fake}
  end

  defp wait_until(fun, tries \\ 100) do
    cond do
      fun.() -> :ok
      tries == 0 -> flunk("condition not met")
      true -> Process.sleep(5) && wait_until(fun, tries - 1)
    end
  end

  defp synced(server), do: Server.health(server)

  test "successful calls make the provider healthy and update stats" do
    {server, _fake} = start()
    assert Server.state(server) == :unknown
    assert {:ok, quote} = Server.request_quote(server, %{id: "r"})
    assert {:ok, _} = Server.invoke(server, :pay, [quote, "k"])
    assert {:ok, _} = Server.status(server, :pay, "k")
    assert %{samples: 3} = Server.health(server) |> ActorTxSpike.Provider.Health.stats()
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

  test "adapter exceptions and exits become errors and count as failures" do
    {:ok, server} = Server.start_link(adapter: Misbehaving, config: :raise)
    assert {:error, {:raised, %RuntimeError{}}} = Server.request_quote(server, %{id: "r"})

    {:ok, server2} = Server.start_link(adapter: Misbehaving, config: :exit)
    assert {:error, {:exit, :gone}} = Server.request_quote(server2, %{id: "r"})
    assert %{window: [{:error, _}]} = synced(server2)
  end

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

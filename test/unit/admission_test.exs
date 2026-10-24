defmodule ActorTxSpike.AdmissionTest do
  @moduledoc """
  Admission control is one named process for the whole system, so these tests run on their
  own (not concurrently with others) and put the limit back afterwards.
  """
  use ExUnit.Case, async: false

  alias ActorTxSpike.{Admission, Policy, Wait}
  alias ActorTxSpike.Provider.{Fake, Server}

  setup do
    on_exit(fn -> Admission.configure(:infinity) end)
    :ok
  end

  defp id, do: "adm#{System.unique_integer([:positive])}"

  defp in_flight, do: Admission.stats().in_flight

  test "up to the limit is admitted; beyond it is refused with a retry hint, and counted" do
    first = id()
    {:ok, :new} = Admission.admit(first)
    # make the limit exactly what is running now, so the next one must be refused
    :ok = Admission.configure(in_flight())
    before = Admission.stats()
    second = id()

    assert {:error, {:overloaded, retry_ms}} = Admission.admit(second)
    assert retry_ms > 0
    assert Admission.stats().rejected == before.rejected + 1
    # the refused one holds no slot
    assert Admission.stats().in_flight == before.in_flight

    Admission.release(first)
    Wait.until!(fn -> in_flight() == before.in_flight - 1 end)
    assert {:ok, :new} = Admission.admit(second)
    Admission.release(second)
  end

  test "a running id is admitted again without being counted twice" do
    :ok = Admission.configure(:infinity)
    tx = id()
    before = Admission.stats()

    assert {:ok, :new} = Admission.admit(tx)
    assert {:ok, :existing} = Admission.admit(tx)
    assert Admission.stats().admitted == before.admitted + 1
    Admission.release(tx)
  end

  test "release frees the slot; releasing something unknown is harmless" do
    tx = id()
    {:ok, :new} = Admission.admit(tx)
    n = in_flight()
    Admission.release(tx)
    Admission.release("never-admitted")
    Wait.until!(fn -> in_flight() == n - 1 end)
  end

  test "the limit is validated" do
    assert :ok = Admission.configure(5)
    assert Admission.stats().limit == 5
    assert :ok = Admission.configure(:infinity)
    assert {:error, message} = Admission.configure(0)
    assert message =~ "positive whole number"
    assert {:error, ^message} = Admission.configure("lots")
    assert message == "the limit must be a positive whole number, or none"
  end

  test "reset zeroes the counters but keeps what is running" do
    tx = id()
    {:ok, :new} = Admission.admit(tx)
    assert :ok = Admission.reset()
    stats = Admission.stats()
    assert stats.admitted == 0 and stats.rejected == 0
    assert stats.peak == stats.in_flight and stats.in_flight >= 1
    Admission.release(tx)
  end

  test "counters: admitted, rejected and the peak" do
    :ok = Admission.configure(:infinity)
    before = Admission.stats()
    ids = for _ <- 1..3, do: id()
    for tx <- ids, do: {:ok, :new} = Admission.admit(tx)
    stats = Admission.stats()
    assert stats.admitted == before.admitted + 3
    assert stats.peak >= before.in_flight + 3
    assert stats.retry_after_ms > 0
    for tx <- ids, do: Admission.release(tx)
  end

  test "a transaction process that dies frees its slot" do
    tx = id()
    {:ok, :new} = Admission.admit(tx)
    n = in_flight()
    pid = spawn(fn -> Process.sleep(:infinity) end)
    Admission.started(tx, pid)
    Process.exit(pid, :kill)
    Wait.until!(fn -> in_flight() == n - 1 end)
  end

  test "hearing that a payment started after it already ended leaves nothing behind" do
    tx = id()
    {:ok, :new} = Admission.admit(tx)
    Admission.release(tx)
    n = in_flight()
    pid = spawn(fn -> Process.sleep(:infinity) end)
    Admission.started(tx, pid)
    Process.exit(pid, :kill)
    Process.sleep(30)
    assert in_flight() == n
    # a new transaction with the same id is not freed by the old process's death
    {:ok, :new} = Admission.admit(tx)
    assert in_flight() == n + 1
    Admission.release(tx)
  end

  describe "at the front door" do
    defp market do
      {:ok, fake} = Fake.start_link(id: :slow, rate: 1.1, latency_ms: 150)
      {:ok, server} = Server.start_link(adapter: Fake, config: fake, id: :slow)
      [%{id: :slow, server: server}]
    end

    defp policy do
      {:ok, p} = Policy.new(%{"weights" => %{"cost" => 1}})
      p
    end

    defp submit(providers, tx) do
      ActorTxSpike.submit(%{id: tx, amount: 100},
        providers: providers,
        policy: policy(),
        opts: [deadline_ms: 2_000]
      )
    end

    test "a submission over the limit is refused before anything starts, and the slot comes back when one ends" do
      assert in_flight() == 0, "this test needs a quiet system"
      :ok = Admission.configure(1)
      providers = market()
      {first, second} = {id(), id()}

      assert {:ok, ^first} = submit(providers, first)
      assert {:error, {:overloaded, _ms}} = submit(providers, second)
      assert ActorTxSpike.snapshot(second) == nil

      # the same id again is the same transaction, not an extra one
      assert {:ok, ^first} = submit(providers, first)

      assert {:ok, _} = ActorTxSpike.await(first, 5_000)
      Wait.until!(fn -> in_flight() == 0 end)
      assert {:ok, ^second} = submit(providers, second)
      assert {:ok, _} = ActorTxSpike.await(second, 5_000)
    end

    test "a refused duplicate of a finished transaction does not leak a slot" do
      assert in_flight() == 0, "this test needs a quiet system"
      providers = market()
      tx = id()
      assert {:ok, ^tx} = submit(providers, tx)
      assert {:ok, _} = ActorTxSpike.await(tx, 5_000)
      Wait.until!(fn -> in_flight() == 0 end)

      # the finished transaction is remembered (its process may already be gone): the id is taken, so this is a no-op
      assert {:ok, ^tx} = submit(providers, tx)
      Wait.until!(fn -> in_flight() == 0 end)
    end
  end
end

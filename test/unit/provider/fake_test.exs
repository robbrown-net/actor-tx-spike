defmodule ActorTxSpike.Provider.FakeTest do
  use ExUnit.Case, async: true

  alias ActorTxSpike.Provider.{Fake, Quote}

  setup do
    {:ok, pid} = Fake.start_link(id: :f, rate: 1.1, fee: 2.0)
    {:ok, fake: pid}
  end

  defp quoted(fake) do
    {:ok, q} = Fake.request_quote(fake, %{id: "r1"})
    q
  end

  test "quotes carry rate, fee and a future expiry", %{fake: fake} do
    assert {:ok, %Quote{provider: :f, request_id: "r1", rate: 1.1, fee: 2.0} = q} =
             Fake.request_quote(fake, %{id: "r1"})

    refute Quote.expired?(q, System.system_time(:millisecond))
  end

  test "quote modes", %{fake: fake} do
    Fake.configure(fake, quote_mode: :reject)
    assert {:rejected, :no_liquidity} = Fake.request_quote(fake, %{id: "r"})
    Fake.configure(fake, quote_mode: :timeout)
    assert :timeout = Fake.request_quote(fake, %{id: "r"})
    Fake.configure(fake, quote_mode: {:error, :boom})
    assert {:error, :boom} = Fake.request_quote(fake, %{id: "r"})
  end

  test "latency is simulated", %{fake: fake} do
    Fake.configure(fake, latency_ms: 20)
    t0 = System.monotonic_time(:millisecond)
    Fake.probe(fake)
    assert System.monotonic_time(:millisecond) - t0 >= 20
  end

  test "execute is idempotent per key and recorded", %{fake: fake} do
    q = quoted(fake)
    assert {:ok, receipt} = Fake.execute(fake, q, "k1")
    assert {:ok, ^receipt} = Fake.execute(fake, q, "k1")
    assert Fake.execute_calls(fake) == 2
    assert Map.keys(Fake.executions(fake)) == ["k1"]
  end

  test "execute failure modes do not execute", %{fake: fake} do
    q = quoted(fake)
    Fake.configure(fake, execute_mode: :timeout)
    assert :timeout = Fake.execute(fake, q, "k")
    Fake.configure(fake, execute_mode: {:error, :down})
    assert {:error, :down} = Fake.execute(fake, q, "k")
    Fake.configure(fake, execute_mode: :reject)
    assert {:rejected, :declined} = Fake.execute(fake, q, "k")
    assert Fake.executions(fake) == %{}
  end

  test "an expired quote is rejected", %{fake: fake} do
    Fake.configure(fake, quote_ttl_ms: -1)
    q = quoted(fake)
    assert {:rejected, :quote_expired} = Fake.execute(fake, q, "k")
  end

  test "in_doubt executes but loses the answer; status can resolve it", %{fake: fake} do
    q = quoted(fake)
    Fake.configure(fake, execute_mode: :in_doubt)
    assert :in_doubt = Fake.execute(fake, q, "k")
    assert {:ok, %{key: "k"}} = Fake.status(fake, "k")
    assert :not_found = Fake.status(fake, "other")
  end

  test "in_doubt retried with the same key does not duplicate the payment", %{fake: fake} do
    q = quoted(fake)
    Fake.configure(fake, execute_mode: :in_doubt)
    :in_doubt = Fake.execute(fake, q, "k")
    :in_doubt = Fake.execute(fake, q, "k")
    assert map_size(Fake.executions(fake)) == 1
  end

  test "status modes", %{fake: fake} do
    Fake.configure(fake, status_mode: :unknown)
    assert :unknown = Fake.status(fake, "k")
    Fake.configure(fake, status_mode: :timeout)
    assert :timeout = Fake.status(fake, "k")
  end

  test "probe reports version and can fail", %{fake: fake} do
    assert {:ok, %{version: "1"}} = Fake.probe(fake)
    Fake.configure(fake, version: "2")
    assert {:ok, %{version: "2"}} = Fake.probe(fake)
    Fake.configure(fake, probe_mode: {:error, :gone})
    assert {:error, :gone} = Fake.probe(fake)
  end
end

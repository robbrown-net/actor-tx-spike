defmodule ActorTxSpike.Provider.FakeTest do
  use ExUnit.Case, async: true

  alias ActorTxSpike.Events
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
    assert {:ok, %Quote{provider: :f, request_id: "r1", rate: 110_000_000, fee: 200} = q} =
             Fake.request_quote(fake, %{id: "r1"})

    refute Quote.expired?(q, System.system_time(:millisecond))
  end

  test "rate and fee settings are normalised to exact integers", %{fake: fake} do
    Fake.configure(fake, rate: "1.25", fee: 0.5)
    assert %{rate: 125_000_000, fee: 50} = Fake.settings(fake)
    Fake.configure(fake, rate: 2)
    assert %{rate: 200_000_000} = Fake.settings(fake)
    Fake.configure(fake, rate: 130_000_000)
    assert %{rate: 130_000_000} = Fake.settings(fake)
  end

  test "a quote request that names an amount is narrated with it", %{fake: fake} do
    {:ok, _} = Fake.request_quote(fake, %{id: "amt-1", amount: 12_345})
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

  test "each lifecycle op is an idempotent, recorded effect", %{fake: fake} do
    q = quoted(fake)

    for op <- [:accept_quote, :authorise, :pay] do
      assert {:ok, receipt} = apply(Fake, op, [fake, q, "tx:k"])
      assert {:ok, ^receipt} = apply(Fake, op, [fake, q, "tx:k"])
      assert Fake.effect_count(fake, op) == 1
    end

    for op <- [:cancel_accept, :void_authorisation, :refund] do
      assert {:ok, _} = apply(Fake, op, [fake, "tx:k"])
      assert {:ok, _} = apply(Fake, op, [fake, "tx:k"])
      assert Fake.effect_count(fake, op) == 1
    end

    assert Fake.calls(fake)[:pay] == 2
    assert map_size(Fake.effects(fake)) == 6
  end

  test "failure modes do not perform the effect", %{fake: fake} do
    q = quoted(fake)
    Fake.configure(fake, pay_mode: :timeout)
    assert :timeout = Fake.pay(fake, q, "t:k")
    Fake.configure(fake, pay_mode: {:error, :down})
    assert {:error, :down} = Fake.pay(fake, q, "t:k")
    Fake.configure(fake, pay_mode: :reject)
    assert {:rejected, :declined} = Fake.pay(fake, q, "t:k")
    assert Fake.effect_count(fake, :pay) == 0
  end

  test "an expired quote is rejected", %{fake: fake} do
    Fake.configure(fake, quote_ttl_ms: -1)
    q = quoted(fake)
    assert {:rejected, :quote_expired} = Fake.accept_quote(fake, q, "t:k")
  end

  test "in_doubt performs the effect but loses the answer; status resolves it", %{fake: fake} do
    q = quoted(fake)
    Fake.configure(fake, pay_mode: :in_doubt)
    assert :in_doubt = Fake.pay(fake, q, "t:k")
    assert {:ok, %{key: "t:k", op: :pay}} = Fake.status(fake, :pay, "t:k")
    assert :not_found = Fake.status(fake, :pay, "t:other")
    assert :not_found = Fake.status(fake, :refund, "t:k")
  end

  @tag :regression

  test "in_doubt retried with the same key does not duplicate the payment", %{fake: fake} do
    q = quoted(fake)
    Fake.configure(fake, pay_mode: :in_doubt)
    :in_doubt = Fake.pay(fake, q, "t:k")
    :in_doubt = Fake.pay(fake, q, "t:k")
    assert Fake.effect_count(fake, :pay) == 1
  end

  test "scripted mode lists are consumed one per call, the last sticks", %{fake: fake} do
    q = quoted(fake)
    Fake.configure(fake, pay_mode: [:timeout, {:error, :x}, :ok])
    assert :timeout = Fake.pay(fake, q, "t:k")
    assert {:error, :x} = Fake.pay(fake, q, "t:k")
    assert {:ok, _} = Fake.pay(fake, q, "t:k")
    assert {:ok, _} = Fake.pay(fake, q, "t:k")
  end

  test "status modes", %{fake: fake} do
    Fake.configure(fake, status_mode: :unknown)
    assert :unknown = Fake.status(fake, :pay, "t:k")
    Fake.configure(fake, status_mode: :timeout)
    assert :timeout = Fake.status(fake, :pay, "t:k")
  end

  test "probe reports version and can fail", %{fake: fake} do
    assert {:ok, %{version: "1"}} = Fake.probe(fake)
    Fake.configure(fake, version: "2")
    assert {:ok, %{version: "2"}} = Fake.probe(fake)
    Fake.configure(fake, probe_mode: {:error, :gone})
    assert {:error, :gone} = Fake.probe(fake)
    assert %{probe_mode: {:error, :gone}} = Fake.settings(fake)
  end

  test "the fake narrates receiving, processing and responding" do
    {:ok, fake} = Fake.start_link(id: :narrator, latency_ms: 5)
    Events.subscribe()
    {:ok, q} = Fake.request_quote(fake, %{id: "story-1", amount: 100})
    {:ok, _} = Fake.accept_quote(fake, q, "story-1:narrator:accept")

    assert_receive {:tx_event,
                    %{kind: :received, tx_id: "story-1", actor: {:provider, :narrator}}}

    assert_receive {:tx_event,
                    %{kind: :processing, tx_id: "story-1", message: "processing (takes ~5ms)"}}

    assert_receive {:tx_event,
                    %{
                      kind: :responded,
                      tx_id: "story-1",
                      data: %{op: :request_quote, result: :ok}
                    }}

    assert_receive {:tx_event, %{kind: :responded, data: %{op: :accept_quote, result: :ok}}}
  end

  test "responses of every shape are described in the narrative" do
    {:ok, fake} = Fake.start_link(id: :desc)
    Events.subscribe()
    Fake.configure(fake, quote_mode: :reject)
    Fake.request_quote(fake, %{id: "d1"})
    Fake.configure(fake, quote_mode: {:error, :boom})
    Fake.request_quote(fake, %{id: "d1"})
    Fake.configure(fake, quote_mode: :timeout)
    Fake.request_quote(fake, %{id: "d1"})

    assert_receive {:tx_event, %{message: "request_quote -> rejected (no_liquidity)"}}
    assert_receive {:tx_event, %{message: "request_quote -> error (:boom)"}}
    assert_receive {:tx_event, %{message: "request_quote -> timeout"}}
  end
end

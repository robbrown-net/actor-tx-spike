defmodule ActorTxSpike.ProviderResilienceIntegrationTest do
  use ExUnit.Case, async: true

  alias ActorTxSpike.Provider.{Fake, Server}

  defp provider(id, fake_opts, health \\ []) do
    {:ok, fake} = Fake.start_link([id: id] ++ fake_opts)
    {:ok, server} = Server.start_link(adapter: Fake, config: fake, health: health)
    %{id: id, fake: fake, server: server}
  end

  defp wait_until(fun, tries \\ 100) do
    cond do
      fun.() -> :ok
      tries == 0 -> flunk("condition not met")
      true -> Process.sleep(5) && wait_until(fun, tries - 1)
    end
  end

  test "a stalled provider is isolated by its breaker while others keep serving" do
    health = [failure_threshold: 3, cool_down_ms: 25, slow_ms: 40]
    good = provider(:good, [rate: 1.2], health)
    flaky = provider(:flaky, [rate: 1.3], health)

    Fake.configure(flaky.fake, quote_mode: :timeout)
    for _ <- 1..3, do: assert(:timeout = Server.request_quote(flaky.server, %{id: "r"}))
    wait_until(fn -> Server.state(flaky.server) == :stalled end)

    assert {:error, :circuit_open} = Server.request_quote(flaky.server, %{id: "r"})
    assert {:ok, %{provider: :good}} = Server.request_quote(good.server, %{id: "r"})
    assert Server.state(good.server) == :healthy

    # flaky recovers: after cool-down a single probe call closes the circuit
    Fake.configure(flaky.fake, quote_mode: :ok)
    Process.sleep(35)
    assert {:ok, %{provider: :flaky}} = Server.request_quote(flaky.server, %{id: "r"})
    wait_until(fn -> Server.state(flaky.server) == :degraded end)

    # ...and earns :healthy back gradually
    for _ <- 1..2, do: assert({:ok, _} = Server.request_quote(flaky.server, %{id: "r"}))
    wait_until(fn -> Server.state(flaky.server) == :healthy end)
  end

  test "slow responses degrade a provider without stalling it" do
    slow = provider(:slow, [latency_ms: 30], slow_ms: 20)
    assert {:ok, _} = Server.request_quote(slow.server, %{id: "r"})
    wait_until(fn -> Server.state(slow.server) == :degraded end)
  end

  test "silent version change quarantines a provider until reviewed" do
    p = provider(:p, [])
    {:ok, _} = Server.probe(p.server)
    Fake.configure(p.fake, version: "2")
    {:ok, _} = Server.probe(p.server)

    assert Server.state(p.server) == :quarantined
    assert {:error, :quarantined} = Server.request_quote(p.server, %{id: "r"})
    Server.release(p.server)
    assert {:ok, _} = Server.request_quote(p.server, %{id: "r"})
  end

  test "in-doubt payment is resolved by status lookup and never double-executed" do
    p = provider(:p, [])
    {:ok, quote} = Server.request_quote(p.server, %{id: "r"})

    Fake.configure(p.fake, pay_mode: :in_doubt)
    assert :in_doubt = Server.invoke(p.server, :pay, [quote, "pay-1"])

    # provider answered "in doubt": reconcile before trying anything else
    assert {:ok, %{key: "pay-1"}} = Server.status(p.server, :pay, "pay-1")

    # a blind retry with the same idempotency key must not pay twice
    assert :in_doubt = Server.invoke(p.server, :pay, [quote, "pay-1"])
    assert Fake.effect_count(p.fake, :pay) == 1
  end
end

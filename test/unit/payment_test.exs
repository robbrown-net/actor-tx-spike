defmodule ActorTxSpike.PaymentTest do
  use ExUnit.Case, async: true

  alias ActorTxSpike.{Events, Payment, Policy}
  alias ActorTxSpike.Provider.{Fake, Server}

  @fast [backoff_ms: 5, reconcile_interval_ms: 5, step_timeout_ms: 500, deadline_ms: 500]

  # Providers are ranked by rate (cost-only policy): :best > :second > :third.
  defp market(overrides \\ %{}) do
    base = %{best: [rate: 1.10], second: [rate: 1.05], third: [rate: 1.00]}

    for {id, opts} <- Map.merge(base, overrides), into: %{} do
      {:ok, fake} = Fake.start_link([id: id] ++ opts)

      {:ok, server} =
        Server.start_link(adapter: Fake, config: fake, id: id, health: [failure_threshold: 50])

      {id, %{id: id, fake: fake, server: server}}
    end
  end

  defp policy do
    {:ok, p} = Policy.new(%{"weights" => %{"cost" => 1}})
    p
  end

  defp submit(market, opts \\ [], extra \\ []) do
    id = "tx#{System.unique_integer([:positive])}"
    providers = market |> Map.values() |> Enum.map(&%{id: &1.id, server: &1.server})

    {:ok, ^id} =
      ActorTxSpike.submit(
        %{id: id, amount: 100},
        providers: providers,
        policy: Keyword.get(extra, :policy, policy()),
        opts: Keyword.merge(@fast, opts)
      )

    id
  end

  defp count(market, provider, op), do: Fake.effect_count(market[provider].fake, op)

  test "happy path: quote, accept, authorise, pay with the best provider" do
    m = market()
    id = submit(m)

    assert {:ok, %{provider: :best, steps: [:accept_quote, :authorise, :pay], rate: 1.10}} =
             Payment.await(id)

    for op <- [:accept_quote, :authorise, :pay], do: assert(count(m, :best, op) == 1)
    assert count(m, :second, :pay) == 0

    assert %{phase: :completed, provider: :best, done: [:accept_quote, :authorise, :pay]} =
             Payment.snapshot(id)
  end

  test "a rejection at accept fails over to the next-best provider with nothing to undo" do
    m = market(%{best: [rate: 1.10, accept_quote_mode: :reject]})
    id = submit(m)

    assert {:ok, %{provider: :second}} = Payment.await(id)
    assert count(m, :best, :cancel_accept) == 0
    assert count(m, :second, :pay) == 1
  end

  test "transient error on a step is retried with the same key and succeeds" do
    m = market(%{best: [rate: 1.10, authorise_mode: [{:error, :blip}, :ok]]})
    id = submit(m)

    assert {:ok, %{provider: :best}} = Payment.await(id)
    assert Fake.calls(m.best.fake)[:authorise] == 2
    assert count(m, :best, :authorise) == 1
  end

  test "a step that keeps erroring is compensated and failed over" do
    m = market(%{best: [rate: 1.10, authorise_mode: {:error, :down}]})
    id = submit(m)

    assert {:ok, %{provider: :second}} = Payment.await(id)
    assert Fake.calls(m.best.fake)[:authorise] == 2
    assert count(m, :best, :cancel_accept) == 1
  end

  test "rejection at authorise undoes the accept, then fails over" do
    m = market(%{best: [rate: 1.10, authorise_mode: :reject]})
    id = submit(m)

    assert {:ok, %{provider: :second}} = Payment.await(id)
    assert count(m, :best, :accept_quote) == 1
    assert count(m, :best, :cancel_accept) == 1
    assert count(m, :best, :pay) == 0
  end

  test "rejection at pay undoes authorisation and acceptance in reverse order" do
    m = market(%{best: [rate: 1.10, pay_mode: :reject]})
    id = submit(m)

    assert {:ok, %{provider: :second}} = Payment.await(id)
    assert count(m, :best, :void_authorisation) == 1
    assert count(m, :best, :cancel_accept) == 1
    assert count(m, :best, :refund) == 0
    assert count(m, :second, :pay) == 1
  end

  test "an accept whose answer was lost is reconciled via status, not repeated" do
    m = market(%{best: [rate: 1.10, accept_quote_mode: :in_doubt]})
    id = submit(m)

    assert {:ok, %{provider: :best}} = Payment.await(id)
    assert count(m, :best, :accept_quote) == 1
    assert count(m, :best, :cancel_accept) == 0
  end

  test "an accept we cannot confirm is cancelled by key and failed over" do
    m = market(%{best: [rate: 1.10, accept_quote_mode: :timeout]})
    id = submit(m)

    assert {:ok, %{provider: :second}} = Payment.await(id)
    assert count(m, :best, :accept_quote) == 0
    assert count(m, :best, :cancel_accept) == 1
  end

  test "a payment whose answer was lost is confirmed by status: paid exactly once" do
    m = market(%{best: [rate: 1.10, pay_mode: :in_doubt]})
    id = submit(m)

    assert {:ok, %{provider: :best}} = Payment.await(id)
    assert count(m, :best, :pay) == 1
    assert count(m, :second, :pay) == 0
  end

  test "an unconfirmable payment is never failed over: it goes to needs_review" do
    m = market(%{best: [rate: 1.10, pay_mode: :timeout]})
    id = submit(m, reconcile_attempts: 2)

    assert {:error, {:needs_review, {:in_doubt, :pay}}} = Payment.await(id)
    assert count(m, :second, :pay) == 0
    assert count(m, :second, :accept_quote) == 0
    assert count(m, :best, :refund) == 0
    assert Fake.calls(m.best.fake)[:status] == 2
  end

  test "payment confirmation that arrives late (stale status) is still honoured" do
    m = market(%{best: [rate: 1.10, pay_mode: :in_doubt, status_mode: [:unknown, :ok]]})
    id = submit(m)

    assert {:ok, %{provider: :best}} = Payment.await(id)
    assert Fake.calls(m.best.fake)[:status] == 2
    assert count(m, :best, :pay) == 1
  end

  test "failed compensation is escalated, not ignored" do
    rejecting =
      market(%{best: [rate: 1.10, authorise_mode: :reject, cancel_accept_mode: :timeout]})

    id = submit(rejecting, max_attempts: 2)

    assert {:error, {:needs_review, {:compensation_failed, :cancel_accept, :timeout}}} =
             Payment.await(id)

    assert Fake.calls(rejecting.best.fake)[:cancel_accept] == 2
    assert count(rejecting, :second, :accept_quote) == 0

    refusing = market(%{best: [rate: 1.10, authorise_mode: :reject, cancel_accept_mode: :reject]})
    id = submit(refusing)

    assert {:error,
            {:needs_review, {:compensation_failed, :cancel_accept, {:rejected, :declined}}}} =
             Payment.await(id)
  end

  test "a compensation that fails once is retried" do
    m =
      market(%{
        best: [rate: 1.10, authorise_mode: :reject, cancel_accept_mode: [{:error, :blip}, :ok]]
      })

    id = submit(m)

    assert {:ok, %{provider: :second}} = Payment.await(id)
    assert Fake.calls(m.best.fake)[:cancel_accept] == 2
  end

  test "when every provider fails the transaction fails with the last reason" do
    all_reject = %{
      best: [rate: 1.10, accept_quote_mode: :reject],
      second: [rate: 1.05, accept_quote_mode: :reject],
      third: [rate: 1.00, accept_quote_mode: :reject]
    }

    id = submit(market(all_reject))
    assert {:error, {:exhausted, {:accept_quote, {:rejected, :declined}}}} = Payment.await(id)
  end

  test "no quote at all, and the baseline fallback" do
    timeouts = for id <- [:best, :second, :third], into: %{}, do: {id, [quote_mode: :timeout]}
    id = submit(market(timeouts), deadline_ms: 50)
    assert {:error, :no_quote} = Payment.await(id)

    id = submit(market(timeouts), deadline_ms: 50, baseline: :usual_route)
    assert {:error, {:no_quote, {:fallback, :usual_route}}} = Payment.await(id)
  end

  test "a quote that expires while we fail over is skipped" do
    m =
      market(%{
        best: [rate: 1.10, accept_quote_mode: {:error, :down}],
        second: [rate: 1.05, quote_ttl_ms: 40],
        third: [rate: 1.00, quote_ttl_ms: 40]
      })

    id = submit(m, backoff_ms: 80)
    assert {:error, {:exhausted, _}} = Payment.await(id)
    assert count(m, :second, :accept_quote) == 0
  end

  test "await before completion waits; unknown ids and timeouts are errors, not crashes" do
    m = market(%{best: [rate: 1.10, latency_ms: 30]})
    id = submit(m)
    task = Task.async(fn -> Payment.await(id) end)
    assert {:ok, _} = Task.await(task)

    assert {:error, :not_found} = Payment.await("nope")
    assert nil == Payment.snapshot("nope")

    slow = market(%{best: [rate: 1.10, latency_ms: 300]})
    id = submit(slow, deadline_ms: 400, step_timeout_ms: 400)
    assert {:error, :timeout} = Payment.await(id, 20)
  end

  test "finished transactions linger and then stop" do
    id = submit(market(), linger_ms: 20)
    assert {:ok, _} = Payment.await(id)
    assert {:ok, _} = Payment.await(id)
    Process.sleep(60)
    assert {:error, :not_found} = Payment.await(id)
  end

  test "the whole story is narrated in order" do
    Events.subscribe()
    m = market(%{best: [rate: 1.10, authorise_mode: :reject]})
    id = submit(m)
    assert {:ok, _} = Payment.await(id)
    Process.sleep(20)

    kinds =
      for {:tx_event, %{tx_id: ^id, actor: :payment, kind: kind}} <- drain(), do: kind

    assert Enum.take(kinds, 4) == [:tx_received, :provider_chosen, :step_started, :step_succeeded]
    assert :step_failed in kinds
    assert :undone in kinds
    assert :failover in kinds
    assert List.last(kinds) == :tx_completed
  end

  test "submit/2 generates ids and tolerates a duplicate id" do
    providers = for {_, p} <- market(), do: %{id: p.id, server: p.server}
    opts = [providers: providers, policy: policy(), opts: @fast]

    assert {:ok, "tx-" <> _ = id} = ActorTxSpike.submit(%{amount: 10}, opts)
    assert {:ok, ^id} = ActorTxSpike.submit(%{id: id, amount: 10}, opts)
    assert {:ok, %{id: ^id}} = ActorTxSpike.await(id)
    assert %{phase: :completed} = ActorTxSpike.snapshot(id)
  end

  test "submit/2 returns startup errors instead of crashing" do
    Process.flag(:trap_exit, true)

    assert {:error, _} =
             ActorTxSpike.submit(%{id: "bad-opts", amount: 1},
               providers: [],
               policy: policy(),
               opts: :not_a_keyword_list
             )
  end

  defp drain(acc \\ []) do
    receive do
      {:tx_event, _} = e -> drain([e | acc])
    after
      0 -> Enum.reverse(acc)
    end
  end
end

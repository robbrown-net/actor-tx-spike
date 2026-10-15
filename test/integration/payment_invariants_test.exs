defmodule ActorTxSpike.PaymentInvariantsIntegrationTest do
  @moduledoc """
  Randomised fault storm through the public API. We do not script outcomes; we
  assert the properties that must hold *whatever* the providers do:

  1. every transaction reaches a terminal state;
  2. no transaction is ever paid more than once (across all providers);
  3. a completed transaction is paid exactly once, by the provider it reports;
  4. a failed transaction was never paid;
  5. when we fail over, whatever we accepted/authorised is cancelled/voided
     (unless the transaction ended in :needs_review, which is the escalation).
  """
  use ExUnit.Case, async: true

  alias ActorTxSpike.Policy
  alias ActorTxSpike.Provider.{Fake, Server}

  @providers [:alpha, :bravo, :charlie, :delta]
  @tx_count 40
  @fast [backoff_ms: 3, reconcile_interval_ms: 3, step_timeout_ms: 300, deadline_ms: 300]

  @fault_pool List.duplicate(:ok, 7) ++ [:timeout, {:error, :glitch}, :reject, :in_doubt]
  @undo_pool List.duplicate(:ok, 5) ++ [:timeout, {:error, :glitch}]
  @status_pool List.duplicate(:ok, 5) ++ [:unknown, :timeout]

  defp script(pool, n), do: for(_ <- 1..n, do: Enum.random(pool))

  defp build_market do
    for {id, i} <- Enum.with_index(@providers), into: %{} do
      {:ok, fake} =
        Fake.start_link(
          id: id,
          rate: 1.0 + i / 100,
          latency_ms: Enum.random(0..4),
          accept_quote_mode: script(@fault_pool, 6),
          authorise_mode: script(@fault_pool, 6),
          pay_mode: script(@fault_pool, 6),
          cancel_accept_mode: script(@undo_pool, 6),
          void_authorisation_mode: script(@undo_pool, 6),
          status_mode: script(@status_pool, 6)
        )

      {:ok, server} =
        Server.start_link(adapter: Fake, config: fake, id: id, health: [failure_threshold: 1_000])

      {id, %{id: id, fake: fake, server: server}}
    end
  end

  defp effects_for(market, tx_id, op) do
    for {pid_id, %{fake: fake}} <- market,
        {{^op, key}, _receipt} <- Fake.effects(fake),
        String.starts_with?(key, tx_id <> ":"),
        do: {pid_id, key}
  end

  test "money-safety invariants hold under a random fault storm" do
    :rand.seed(:exsss, {7, 11, 13})
    market = build_market()
    providers = for {_, p} <- market, do: %{id: p.id, server: p.server}

    {:ok, policy} =
      Policy.new(%{"weights" => %{"cost" => 0.7, "speed" => 0.2, "reliability" => 0.1}})

    results =
      1..@tx_count
      |> Task.async_stream(
        fn n ->
          id = "storm#{n}"

          {:ok, ^id} =
            ActorTxSpike.submit(%{id: id, amount: 100 + n},
              providers: providers,
              policy: policy,
              opts: @fast
            )

          {id, ActorTxSpike.await(id, 10_000)}
        end,
        max_concurrency: 16,
        timeout: 15_000
      )
      |> Enum.map(fn {:ok, r} -> r end)

    assert length(results) == @tx_count

    for {id, result} <- results do
      pays = effects_for(market, id, :pay)
      accepts = effects_for(market, id, :accept_quote)
      authorises = effects_for(market, id, :authorise)
      cancels = effects_for(market, id, :cancel_accept)
      voids = effects_for(market, id, :void_authorisation)

      # 2. never paid twice
      assert length(pays) <= 1, "#{id} paid #{length(pays)} times: #{inspect(pays)}"

      case result do
        {:ok, %{provider: provider}} ->
          # 3. exactly once, by the reported provider
          assert [{^provider, _key}] = pays
          # 5. everything at *other* providers was undone
          for {p, key} <- accepts, p != provider, do: assert({p, key} in cancels)
          for {p, key} <- authorises, p != provider, do: assert({p, key} in voids)

        {:error, {:needs_review, _}} ->
          :ok

        {:error, _reason} ->
          # 4. failed means never paid
          assert pays == []
          # 5. and fully compensated
          for acc <- accepts, do: assert(acc in cancels, "#{id}: #{inspect(acc)} not cancelled")
          for auth <- authorises, do: assert(auth in voids, "#{id}: #{inspect(auth)} not voided")
      end
    end

    outcomes = Enum.frequencies_by(results, fn {_, r} -> elem(r, 0) end)
    assert outcomes[:ok] > 0
  end
end

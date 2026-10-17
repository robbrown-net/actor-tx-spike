defmodule ActorTxSpike.Selector do
  @moduledoc """
  Quote-and-select: fan out a quote request to providers concurrently within a
  deadline, drop those that are unusable, score the rest under a `Policy`, and
  return the best-so-far.

  Quoting has no side effects, so hedging across providers is safe. Providers
  whose health state is excluded by the policy are not called at all (saves
  network and quota). The deadline is a hard bound: late providers count as
  `:timeout` and the health model learns from it.

  The request must carry `:id` and `:amount`; net value to the customer is
  `amount * rate - fee`, all in integer minor units (see `ActorTxSpike.Money`).

  `providers` is a list of `%{id: term, server: pid_or_name}` where `server` is
  an `ActorTxSpike.Provider.Server`.

  Options: `:deadline_ms` (default 1000), `:baseline` (returned as
  `{:fallback, baseline}` when no usable quote arrives).
  """

  alias ActorTxSpike.{Events, Journal, Money, Policy, Selection}
  alias ActorTxSpike.Provider.{Quote, Server}
  alias ActorTxSpike.Selection.Candidate

  # Backstop in case a provider server itself hangs; the per-call deadline normally fires first.
  @grace_ms 100

  @spec select(map(), [map()], Policy.t(), keyword()) :: Selection.t()
  def select(request, providers, %Policy{} = policy, opts \\ []) do
    started = now()
    deadline_ms = Keyword.get(opts, :deadline_ms, 1_000)
    baseline = Keyword.get(opts, :baseline)

    with_state = Enum.map(providers, &{&1, state_of(&1.server)})

    announce(
      request,
      :select_started,
      "asking #{length(providers)} provider(s) for quotes " <>
        "(deadline #{deadline_ms}ms): #{Enum.map_join(providers, ", ", & &1.id)}",
      %{providers: Enum.map(providers, & &1.id), deadline_ms: deadline_ms}
    )

    for {p, state} <- with_state, Policy.exclude_state?(policy, state) do
      announce(request, :provider_skipped, "not calling #{p.id}: it is #{state}", %{
        provider: p.id,
        state: state
      })
    end

    {callable, skipped} =
      Enum.split_with(with_state, fn {_p, state} -> not Policy.exclude_state?(policy, state) end)

    observed_at = System.system_time(:millisecond)

    observations =
      request
      |> fan_out(callable, deadline_ms)
      |> Enum.map(fn {p, state, result, latency} ->
        %{
          provider_id: p.id,
          server: p.server,
          state: state,
          result: result,
          latency_ms: latency,
          observed_at_ms: observed_at
        }
      end)

    Enum.each(observations, &journal_observation(request, &1))

    %{items: items, ranked: ranked, excluded: failures} = evaluate(request, observations, policy)
    Enum.each(items, &announce_item(request, &1))

    outcome =
      case {ranked, baseline} do
        {[best | _], _} -> {:selected, best}
        {[], nil} -> :none
        {[], baseline} -> {:fallback, baseline}
      end

    selection = %Selection{
      outcome: outcome,
      ranked: ranked,
      excluded: Enum.map(skipped, fn {p, state} -> {p.id, {:state, state}} end) ++ failures,
      elapsed_ms: now() - started
    }

    announce_outcome(request, selection)
    selection
  end

  @doc """
  The pure heart of selection: what each provider answered, judged under a
  policy. No processes, no clock, no events, so a recorded day can be judged
  again with the *same code* that made the live decision (see
  `ActorTxSpike.Replay`).

  Each observation is `%{provider_id, server, state, result, latency_ms,
  observed_at_ms}`. Returns `%{items, ranked, excluded}`: `items` are the
  per-provider judgements in order, `ranked` the admissible candidates best
  first, `excluded` the `{provider_id, reason}` of every one that was not.
  """
  @spec evaluate(map(), [map()], Policy.t()) :: %{items: list(), ranked: list(), excluded: list()}
  def evaluate(request, observations, %Policy{} = policy) do
    items = Enum.map(observations, &judge(&1, request, policy))
    candidates = for {:candidate, c} <- items, do: c

    failures =
      for item <- items, reduce: [] do
        acc ->
          case item do
            {:candidate, _} -> acc
            {:excluded, c, reason} -> [{c.provider_id, reason} | acc]
            {:failed, obs, reason} -> [{obs.provider_id, reason} | acc]
          end
      end

    {within, too_dear} = Policy.within_best(policy, candidates)

    failures =
      Enum.reduce(too_dear, failures, fn {c, bps}, acc ->
        [{c.provider_id, {:worse_than_best_bps, Float.round(bps, 1)}} | acc]
      end)

    %{items: items, ranked: rank(within, policy), excluded: Enum.reverse(failures)}
  end

  defp announce_outcome(request, %Selection{outcome: {:selected, best}} = sel) do
    others =
      sel.ranked |> Enum.drop(1) |> Enum.map(&"#{&1.provider_id} (#{Float.round(&1.score, 3)})")

    tail = if others == [], do: "", else: " ahead of #{Enum.join(others, ", ")}"

    announce(
      request,
      :selection_made,
      "selected #{best.provider_id} (score #{Float.round(best.score, 3)}, " <>
        "net #{Money.format(best.net)}, #{best.latency_ms}ms)" <> tail,
      %{
        provider: best.provider_id,
        ranking:
          Enum.map(
            sel.ranked,
            &%{provider: &1.provider_id, score: &1.score, net: &1.net, latency_ms: &1.latency_ms}
          ),
        excluded: describe_excluded(sel),
        elapsed_ms: sel.elapsed_ms
      }
    )
  end

  defp announce_outcome(request, %Selection{outcome: {:fallback, baseline}} = sel) do
    announce(
      request,
      :selection_made,
      "no usable quote; falling back to baseline #{inspect(baseline)}",
      %{excluded: describe_excluded(sel)}
    )
  end

  defp announce_outcome(request, %Selection{outcome: :none} = sel) do
    announce(request, :selection_made, "no usable quote from any provider", %{
      excluded: describe_excluded(sel)
    })
  end

  defp describe_excluded(sel),
    do: Enum.map(sel.excluded, fn {id, why} -> %{provider: id, reason: inspect(why)} end)

  defp announce(request, kind, message, data) do
    Events.emit(request.id, :selector, kind, message, data)
  end

  defp state_of(server) do
    Server.state(server)
  catch
    :exit, _reason -> :down
  end

  defp fan_out(request, callable, deadline_ms) do
    providers = Enum.map(callable, &elem(&1, 0))

    providers
    |> Task.async_stream(&timed_quote(&1, request, deadline_ms),
      timeout: deadline_ms + @grace_ms,
      on_timeout: :kill_task,
      max_concurrency: max(length(providers), 1),
      ordered: true
    )
    |> Enum.zip(callable)
    |> Enum.map(fn
      {{:ok, {result, latency}}, {p, state}} -> {p, state, result, latency}
      {{:exit, :timeout}, {p, state}} -> {p, state, :timeout, deadline_ms}
    end)
  end

  defp timed_quote(provider, request, deadline_ms) do
    started = now()

    result =
      try do
        Server.request_quote(provider.server, request, deadline_ms)
      catch
        :exit, reason -> {:error, {:exit, reason}}
      end

    {result, now() - started}
  end

  defp judge(%{result: {:ok, %Quote{} = quote}} = obs, request, policy) do
    candidate = %Candidate{
      provider_id: obs.provider_id,
      server: obs.server,
      quote: quote,
      net: Money.convert(request.amount, quote.rate) - quote.fee,
      latency_ms: obs.latency_ms,
      validity_ms: quote.expires_at_ms - obs.observed_at_ms,
      state: obs.state
    }

    case Policy.check(policy, candidate) do
      :ok -> {:candidate, candidate}
      {:excluded, reason} -> {:excluded, candidate, reason}
    end
  end

  defp judge(%{result: other} = obs, _request, _policy) do
    reason =
      if match?({:rejected, _}, other) or match?({:error, _}, other), do: other, else: :timeout

    {:failed, obs, reason}
  end

  defp announce_item(request, {:candidate, c}) do
    announce(
      request,
      :quote_received,
      "#{c.provider_id} quoted rate #{Money.format_rate(c.quote.rate)}, fee #{Money.format(c.quote.fee)} (net #{Money.format(c.net)}, #{c.latency_ms}ms)",
      %{provider: c.provider_id, rate: c.quote.rate, fee: c.quote.fee, latency_ms: c.latency_ms}
    )
  end

  defp announce_item(request, {:excluded, c, reason}) do
    announce(request, :quote_excluded, "#{c.provider_id}'s quote is unusable: #{reason}", %{
      provider: c.provider_id,
      reason: reason
    })
  end

  defp announce_item(request, {:failed, obs, reason}) do
    announce(
      request,
      :quote_failed,
      "#{obs.provider_id} gave no quote: #{inspect(reason)} after #{obs.latency_ms}ms",
      %{provider: obs.provider_id, reason: inspect(reason), latency_ms: obs.latency_ms}
    )
  end

  # Journal: the raw answer of one provider, with everything a later replay
  # needs to judge it again (health state at call time, latency, when seen).
  defp journal_observation(request, obs) do
    {kind, detail} =
      case obs.result do
        {:ok, %Quote{} = q} ->
          {:quote,
           %{rate: q.rate, fee: q.fee, expires_at_ms: q.expires_at_ms, request_id: q.request_id}}

        {:rejected, why} ->
          {:rejected, %{reason: inspect(why)}}

        {:error, why} ->
          {:error, %{reason: inspect(why)}}

        _timeout ->
          {:timeout, %{}}
      end

    Journal.record(
      request.id,
      :quote_observed,
      "journal: #{obs.provider_id} answered the quote request (#{kind})",
      Map.merge(detail, %{
        provider: obs.provider_id,
        outcome: kind,
        state: obs.state,
        latency_ms: obs.latency_ms,
        observed_at_ms: obs.observed_at_ms
      })
    )
  end

  defp rank(candidates, policy) do
    candidates
    |> Enum.zip(Policy.scores(policy, candidates))
    |> Enum.map(fn {candidate, score} -> %{candidate | score: score} end)
    |> Enum.sort_by(&{-&1.score, to_string(&1.provider_id)})
  end

  defp now, do: System.monotonic_time(:millisecond)
end

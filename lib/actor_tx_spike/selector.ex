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

  A provider whose bulkhead is full is asked again every few milliseconds until the deadline,
  rather than being given up on at once: under load a quote waits for a slot.

  Options: `:deadline_ms` (default 1000), `:baseline` (returned as
  `{:fallback, baseline}` when no usable quote arrives).
  """

  alias ActorTxSpike.{Money, Policy, Selection}
  alias ActorTxSpike.Provider.{Quote, Server}
  alias ActorTxSpike.Selection.Candidate
  alias ActorTxSpike.Selector.Narrator

  # Backstop in case a provider server itself hangs; the per-call deadline normally fires first.
  @grace_ms 100
  # How long to wait before asking a busy provider again.
  @busy_retry_ms 15

  @spec select(map(), [map()], Policy.t(), keyword()) :: Selection.t()
  def select(request, providers, %Policy{} = policy, opts \\ []) do
    started = now()
    deadline_ms = Keyword.get(opts, :deadline_ms, 1_000)

    Narrator.started(request, providers, deadline_ms)

    {callable, not_called} = screen(request, providers, policy)

    observations = observe(request, callable, deadline_ms)
    Enum.each(observations, &Narrator.observation(request, &1))

    evaluation = evaluate(request, observations, policy)
    Enum.each(evaluation.items, &Narrator.item(request, &1))

    selection = build(evaluation, not_called, Keyword.get(opts, :baseline), now() - started)
    Narrator.outcome(request, selection)
    selection
  end

  # Who is not asked at all, and why: no currency pair, or a health state the policy excludes.
  defp screen(request, providers, policy) do
    {capable, unsupported} = Enum.split_with(providers, &carries?(&1, request))
    Enum.each(unsupported, &Narrator.unsupported(request, &1))
    {callable, skipped} = partition(capable, policy)
    Enum.each(skipped, &Narrator.skipped(request, &1))
    {callable, not_called(request, unsupported, skipped)}
  end

  # Providers whose health state the policy excludes are not asked at all.
  defp partition(providers, policy) do
    providers
    |> Enum.map(&{&1, state_of(&1.server)})
    |> Enum.split_with(fn {_provider, state} -> not Policy.exclude_state?(policy, state) end)
  end

  # A provider that does not carry the request's currency pair is not asked (when the provider
  # lists its currencies and the request names both).
  defp carries?(%{currencies: codes}, %{source_currency: from, destination_currency: to})
       when is_list(codes),
       do: from in codes and to in codes

  defp carries?(_provider, _request), do: true

  defp not_called(request, unsupported, skipped) do
    pair = "#{request[:source_currency]}/#{request[:destination_currency]}"

    Enum.map(unsupported, &{&1.id, {:unsupported, pair}}) ++
      Enum.map(skipped, fn {p, state} -> {p.id, {:state, state}} end)
  end

  defp build(evaluation, not_called, baseline, elapsed_ms) do
    %Selection{
      outcome: outcome(evaluation.ranked, baseline),
      ranked: evaluation.ranked,
      excluded: not_called ++ evaluation.excluded,
      elapsed_ms: elapsed_ms
    }
  end

  defp outcome([best | _], _baseline), do: {:selected, best}
  defp outcome([], nil), do: :none
  defp outcome([], baseline), do: {:fallback, baseline}

  # What every callable provider answered, stamped with when we looked.
  defp observe(request, callable, deadline_ms) do
    observed_at = System.system_time(:millisecond)

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
    {within, too_dear} = Policy.within_best(policy, candidates)

    %{
      items: items,
      ranked: rank(within, policy),
      excluded: refusals(items) ++ Enum.map(too_dear, &worse_than_best/1)
    }
  end

  # Why each judged-out provider was out, in the order they were asked.
  defp refusals(items) do
    for item <- items, reason = refusal(item), do: reason
  end

  defp refusal({:candidate, _}), do: nil
  defp refusal({:excluded, c, reason}), do: {c.provider_id, reason}
  defp refusal({:failed, obs, reason}), do: {obs.provider_id, reason}

  defp worse_than_best({c, bps}), do: {c.provider_id, {:worse_than_best_bps, Float.round(bps, 1)}}

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
        ask(provider, request, deadline_ms, started)
      catch
        :exit, reason -> {:error, {:exit, reason}}
      end

    {result, now() - started}
  end

  # A provider that is busy (its bulkhead is full) has not been asked anything, so asking again
  # shortly is safe: wait for a slot while the deadline allows, rather than give the provider up.
  defp ask(provider, request, deadline_ms, started) do
    remaining = max(deadline_ms - (now() - started), 1)

    case Server.request_quote(provider.server, request, remaining) do
      {:error, :overloaded} = busy ->
        retry_when_busy(busy, provider, request, deadline_ms, started)

      answer ->
        answer
    end
  end

  defp retry_when_busy(busy, provider, request, deadline_ms, started) do
    if now() - started + @busy_retry_ms < deadline_ms do
      Process.sleep(@busy_retry_ms)
      ask(provider, request, deadline_ms, started)
    else
      busy
    end
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

  defp judge(%{result: other} = obs, _request, _policy), do: {:failed, obs, failure(other)}

  defp failure({:rejected, _} = refused), do: refused
  defp failure({:error, _} = error), do: error
  defp failure(_no_answer), do: :timeout

  defp rank(candidates, policy) do
    candidates
    |> Enum.zip(Policy.scores(policy, candidates))
    |> Enum.map(fn {candidate, score} -> %{candidate | score: score} end)
    |> Enum.sort_by(&{-&1.score, to_string(&1.provider_id)})
  end

  defp now, do: System.monotonic_time(:millisecond)
end

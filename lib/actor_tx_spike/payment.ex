defmodule ActorTxSpike.Payment do
  @moduledoc """
  One FX-swap-and-pay transaction, as a process.

  Lifecycle:

      received -> quoting -> accepting -> authorising -> paying -> completed
                                 |            |            |
                                 +-- compensating (undo what happened) --+
                                 |        then fail over to the next-best provider
                                 +-- reconciling (answer lost; ask the provider)
                                          -> needs_review when we cannot be sure

  Rules that keep money safe:

  * **Quoting is free; everything after is effectful.** Each effectful step has
    its own idempotency key `"<tx>:<provider>:<step>"`, reused on retry.
  * **Definite failure** (provider said no / technical error, nothing happened):
    retry the step a bounded number of times, then *compensate* what was
    already done at that provider and *fail over* to the next-ranked quote.
  * **Doubt** (timeout / answer lost): never guess. Ask the provider what it
    holds (`status`). For reversible steps (accept, authorise) if we still
    cannot tell, cancel by key (idempotent, defeats a late effect) and fail
    over. For `pay` we never fail over while in doubt: we keep reconciling and,
    if still unresolved, stop in `:needs_review` instead of risking a double
    payment.
  * Compensation that itself cannot be completed also ends in `:needs_review`.

  Every transition is announced via `ActorTxSpike.Events`.
  """
  use GenServer

  alias ActorTxSpike.{
    Cutoff,
    Eligibility,
    Events,
    Guarantee,
    Journal,
    Ledger,
    Money,
    PolicySet,
    Selector
  }

  alias ActorTxSpike.Provider.{Quote, Server}

  @undo %{accept_quote: :cancel_accept, authorise: :void_authorisation, pay: :refund}
  @phase %{accept_quote: :accepting, authorise: :authorising, pay: :paying, settle: :settling}
  @next %{accept_quote: :authorise, authorise: :pay, pay: :settle, settle: nil}
  @verb %{
    accept_quote: "accept the quote",
    authorise: "authorise the payment",
    pay: "make the payment",
    settle: "settle the payment",
    cancel_accept: "cancel the accepted quote",
    void_authorisation: "void the authorisation",
    refund: "refund"
  }

  @defaults [
    deadline_ms: 1_000,
    step_timeout_ms: 1_000,
    max_attempts: 2,
    backoff_ms: 25,
    reconcile_attempts: 3,
    reconcile_interval_ms: 50,
    linger_ms: 30_000,
    baseline: nil,
    barrier: &Events.barrier/0
  ]

  @terminal [:completed, :failed, :needs_review]

  def start_link(args) do
    GenServer.start_link(__MODULE__, args, name: via(Keyword.fetch!(args, :id)))
  end

  def via(id), do: {:via, Registry, {ActorTxSpike.Registry, {:payment, id}}}

  @doc "Block until terminal: `{:ok, summary}` or `{:error, reason}`."
  def await(id, timeout \\ 5_000) do
    GenServer.call(via(id), :await, timeout)
  catch
    :exit, {:noproc, _} -> {:error, :not_found}
    :exit, {:timeout, _} -> {:error, :timeout}
  end

  def snapshot(id) do
    GenServer.call(via(id), :snapshot)
  catch
    :exit, {:noproc, _} -> nil
  end

  @impl true
  def init(args) do
    request = Keyword.fetch!(args, :request)
    decision = Eligibility.decide(Keyword.get(args, :eligibility, Eligibility.open()), request)

    {eligible, denied} =
      args
      |> Keyword.fetch!(:providers)
      |> Enum.split_with(&Eligibility.provider_allowed?(decision, &1.id))

    state = %{
      id: Keyword.fetch!(args, :id),
      request: request,
      providers: eligible,
      eligibility: Map.put(decision, :denied, denied),
      policy: Keyword.fetch!(args, :policy),
      opts: Map.new(Keyword.merge(@defaults, Keyword.get(args, :opts, []))),
      cutoff: Keyword.get(args, :cutoff),
      deadline_ms: nil,
      policy_layers: Keyword.get(args, :policy_layers, []),
      guarantee: Keyword.get(args, :guarantee),
      ledger: Keyword.get(args, :ledger, Ledger),
      phase: :received,
      candidates: [],
      current: nil,
      done: [],
      attempt: 1,
      undo_queue: [],
      undo_attempt: 1,
      after_undo: nil,
      reconcile_n: 0,
      last_failure: nil,
      pending: nil,
      history: [],
      waiters: [],
      result: nil,
      # economics: what this transaction consumed (see ActorTxSpike.Economics)
      started_ms: System.system_time(:millisecond),
      reductions0: reductions(),
      quote_nets: [],
      calls: 0,
      bytes_out: 0,
      bytes_in: 0,
      compensations: 0
    }

    {:ok, state, {:continue, :start}}
  end

  @impl true
  def handle_continue(:start, state) do
    amount = state.request[:amount]

    state =
      note(state, :tx_received, "transaction received: amount #{Money.format(amount)}", %{
        amount: amount,
        request: state.request,
        policy: Map.from_struct(state.policy),
        guarantee: state.guarantee && Map.from_struct(state.guarantee),
        config: Map.drop(state.opts, [:baseline, :barrier]),
        providers: Enum.map(state.providers, & &1.id),
        eligibility: journal_eligibility(state)
      })

    cond do
      not state.eligibility.permitted? ->
        reasons = state.eligibility.reasons

        {:noreply,
         finish(
           state,
           :failed,
           {:error, {:not_permitted, reasons}},
           :tx_failed,
           "not permitted: #{Enum.join(reasons, "; ")}"
         )}

      state.providers == [] and state.eligibility.denied != [] ->
        {:noreply,
         finish(
           state,
           :failed,
           {:error, :no_eligible_provider},
           :tx_failed,
           "no provider is eligible for the #{Enum.join(state.eligibility.regions, "/")} corridor"
         )}

      true ->
        state = announce_eligibility(state)
        state = announce_policy(state)
        state = announce_deadline(state)
        state = %{state | phase: :quoting}
        {:noreply, run(state, :selection, fn -> do_select(state) end)}
    end
  end

  defp announce_eligibility(%{eligibility: %{regions: []}} = state), do: state

  defp announce_eligibility(%{eligibility: e} = state) do
    note(
      state,
      :eligibility_decided,
      "jurisdiction #{Enum.join(e.regions, "/")}: #{length(state.providers)} of #{length(state.providers) + length(e.denied)} providers eligible; " <>
        "records kept in #{Enum.join(e.logs, ", ")}",
      %{regions: e.regions, logs: e.logs, denied: Enum.map(e.denied, & &1.id)}
    )
  end

  # The decision as recorded: what applied, and who was ruled out and why.
  defp journal_eligibility(%{eligibility: e} = state) do
    e
    |> Map.drop([:denied])
    |> Map.put(:allowed_providers, Enum.map(state.providers, & &1.id))
    |> Map.put(
      :denied_providers,
      for(p <- e.denied, do: %{provider: p.id, reason: Eligibility.denial_reason(e, p.id)})
    )
  end

  @impl true
  def handle_call(:await, from, %{phase: phase} = state) when phase in @terminal do
    GenServer.reply(from, state.result)
    {:noreply, state}
  end

  def handle_call(:await, from, state), do: {:noreply, %{state | waiters: [from | state.waiters]}}

  def handle_call(:snapshot, _from, state) do
    {:reply,
     %{
       id: state.id,
       phase: state.phase,
       provider: state.current && state.current.provider_id,
       deadline_ms: state.deadline_ms,
       done: Enum.reverse(state.done),
       history: Enum.reverse(state.history),
       result: state.result
     }, state}
  end

  @impl true
  def handle_info({ref, {tag, result, latency}}, %{pending: ref} = state) do
    Process.demonitor(ref, [:flush])
    state = %{state | pending: nil, bytes_in: state.bytes_in + :erlang.external_size(result)}
    state = if tag == :selection, do: asked_all(state), else: state
    journal_response(state, tag, result, latency)
    {:noreply, handle_result(tag, result, state)}
  end

  def handle_info({:retry_step, op}, state), do: {:noreply, run_step(state, op)}
  def handle_info({:retry_undo, undo}, state), do: {:noreply, run_undo(state, undo)}
  def handle_info({:reconcile_again, op}, state), do: {:noreply, run_status(state, op)}
  def handle_info(:linger_over, state), do: {:stop, :normal, state}

  # ---- results -----------------------------------------------------------

  defp handle_result(:selection, %{outcome: {:selected, _}} = selection, state) do
    state = %{
      state
      | candidates: selection.ranked,
        quote_nets: Enum.map(selection.ranked, & &1.net),
        bytes_in: state.bytes_in + :erlang.external_size(Enum.map(selection.ranked, & &1.quote))
    }

    try_next_candidate(state)
  end

  defp handle_result(:selection, %{outcome: {:fallback, baseline}}, state) do
    finish(
      state,
      :failed,
      {:error, {:no_quote, {:fallback, baseline}}},
      :tx_failed,
      "no usable quote; baseline route #{inspect(baseline)} is the only option left"
    )
  end

  defp handle_result(:selection, %{outcome: :none}, state) do
    finish(
      state,
      :failed,
      {:error, :no_quote},
      :tx_failed,
      "no provider could give a usable quote"
    )
  end

  defp handle_result({:step, op}, {:ok, _receipt}, state) do
    c = state.current

    state =
      note(state, :step_succeeded, "#{c.provider_id} did #{op}", %{
        provider: c.provider_id,
        op: op
      })

    step_succeeded(state, op)
  end

  defp handle_result({:step, op}, {:error, reason}, state) do
    if state.attempt < state.opts.max_attempts do
      state =
        note(
          state,
          :step_retry,
          "#{state.current.provider_id} errored on #{op} (#{inspect(reason)}); retrying " <>
            "(attempt #{state.attempt + 1}/#{state.opts.max_attempts})",
          %{provider: state.current.provider_id, op: op}
        )

      Process.send_after(self(), {:retry_step, op}, state.opts.backoff_ms * state.attempt)
      %{state | attempt: state.attempt + 1}
    else
      step_failed(state, op, {:error, reason})
    end
  end

  defp handle_result({:step, op}, {:rejected, why}, state),
    do: step_failed(state, op, {:rejected, why})

  defp handle_result({:step, op}, doubt, state) when doubt in [:timeout, :in_doubt] do
    c = state.current

    state =
      note(
        state,
        :in_doubt,
        "#{c.provider_id} gave no definite answer to #{op} (#{doubt}); it may or may not have happened",
        %{provider: c.provider_id, op: op}
      )

    run_status(%{state | reconcile_n: 1}, op)
  end

  defp handle_result({:status, op}, {:ok, _receipt}, state) do
    c = state.current

    state =
      note(state, :reconciled, "#{c.provider_id} confirms #{op} did happen", %{
        provider: c.provider_id,
        op: op
      })

    step_succeeded(state, op)
  end

  defp handle_result({:status, op}, unresolved, state) when op in [:pay, :settle] do
    c = state.current

    if state.reconcile_n < state.opts.reconcile_attempts do
      state =
        note(
          state,
          :reconcile_pending,
          "#{c.provider_id} cannot yet confirm #{op} (#{inspect(unresolved)}); " <>
            "asking again (#{state.reconcile_n}/#{state.opts.reconcile_attempts})",
          %{provider: c.provider_id}
        )

      Process.send_after(self(), {:reconcile_again, op}, state.opts.reconcile_interval_ms)
      %{state | reconcile_n: state.reconcile_n + 1}
    else
      needs_review(
        state,
        {:in_doubt, op},
        "#{op} with #{c.provider_id} is unresolved; NOT failing over (the payment may already have happened)"
      )
    end
  end

  defp handle_result({:status, op}, unresolved, state) do
    c = state.current

    state =
      note(
        state,
        :cancel_by_key,
        "#{c.provider_id} cannot confirm #{op} (#{inspect(unresolved)}); cancelling by key to be safe",
        %{provider: c.provider_id, op: op}
      )

    undos = [{@undo[op], op} | Enum.map(state.done, &{@undo[&1], &1})]
    start_compensation(state, undos, {:failover, {:in_doubt, op}})
  end

  defp handle_result({:undo, undo, _original}, {:ok, _receipt}, state) do
    c = state.current

    state =
      note(state, :undone, "#{c.provider_id} did #{undo}", %{provider: c.provider_id, op: undo})

    next_undo(%{state | undo_queue: tl(state.undo_queue), undo_attempt: 1})
  end

  defp handle_result({:undo, undo, _original}, {:rejected, why}, state) do
    needs_review(
      state,
      {:compensation_failed, undo, {:rejected, why}},
      "#{state.current.provider_id} refused to #{undo}: #{inspect(why)}"
    )
  end

  defp handle_result({:undo, undo, _original}, failure, state) do
    if state.undo_attempt < state.opts.max_attempts do
      state =
        note(
          state,
          :undo_retry,
          "#{undo} at #{state.current.provider_id} failed (#{inspect(failure)}); retrying",
          %{}
        )

      Process.send_after(self(), {:retry_undo, undo}, state.opts.backoff_ms * state.undo_attempt)
      %{state | undo_attempt: state.undo_attempt + 1}
    else
      needs_review(
        state,
        {:compensation_failed, undo, failure},
        "could not #{undo} at #{state.current.provider_id}: #{inspect(failure)}"
      )
    end
  end

  # ---- flow --------------------------------------------------------------

  defp try_next_candidate(state) do
    now = System.system_time(:millisecond)
    {viable, expired} = drop_expired(state.candidates, now)

    state =
      Enum.reduce(expired, state, fn c, acc ->
        note(acc, :quote_expired, "skipping #{c.provider_id}: its quote has expired", %{
          provider: c.provider_id
        })
      end)

    case viable do
      [] ->
        reason = state.last_failure || :no_quote

        finish(
          state,
          :failed,
          {:error, {:exhausted, reason}},
          :tx_failed,
          "every provider has been tried; giving up (#{inspect(reason)})"
        )

      [c | rest] ->
        state =
          note(
            state,
            :provider_chosen,
            "going with #{c.provider_id} (quote rate #{Money.format_rate(c.quote.rate)}, fee #{Money.format(c.quote.fee)})",
            %{provider: c.provider_id, remaining: Enum.map(rest, & &1.provider_id)}
          )

        run_step(%{state | candidates: rest, current: c, done: [], attempt: 1}, :accept_quote)
    end
  end

  defp drop_expired(candidates, now) do
    Enum.split_with(candidates, fn c -> not Quote.expired?(c.quote, now) end)
  end

  defp step_succeeded(state, op) do
    state = %{state | done: [op | state.done], attempt: 1}

    case @next[op] do
      nil -> complete(state)
      next -> run_step(state, next)
    end
  end

  defp step_failed(state, :settle, reason) do
    # The payment already happened and cannot be undone: a human must settle it.
    needs_review(
      state,
      {:settlement_failed, reason},
      "#{state.current.provider_id} could not settle a payment that was already made: #{inspect(reason)}"
    )
  end

  defp step_failed(state, op, reason) do
    c = state.current

    state =
      note(state, :step_failed, "#{c.provider_id} could not #{@verb[op]}: #{inspect(reason)}", %{
        provider: c.provider_id,
        op: op,
        reason: inspect(reason)
      })

    start_compensation(state, Enum.map(state.done, &{@undo[&1], &1}), {:failover, {op, reason}})
  end

  defp start_compensation(state, undos, after_undo) do
    state = %{
      state
      | phase: :compensating,
        undo_queue: undos,
        undo_attempt: 1,
        after_undo: after_undo
    }

    next_undo(state)
  end

  defp next_undo(%{undo_queue: [], after_undo: {:abort, reason}} = state) do
    finish(
      state,
      :failed,
      {:error, reason},
      :tx_failed,
      "stopping: #{inspect(reason)}; everything done so far has been undone"
    )
  end

  defp next_undo(%{undo_queue: []} = state) do
    {:failover, reason} = state.after_undo

    state =
      note(state, :failover, "moving on from #{state.current.provider_id}: #{inspect(reason)}", %{
        provider: state.current.provider_id
      })

    try_next_candidate(%{state | current: nil, done: [], last_failure: reason})
  end

  defp next_undo(%{undo_queue: [{undo, _original} | _]} = state), do: run_undo(state, undo)

  defp complete(state) do
    c = state.current

    {state, guarantee} = settle_guarantee(state, c)

    summary = %{
      id: state.id,
      provider: c.provider_id,
      rate: c.quote.rate,
      fee: c.quote.fee,
      net: c.net,
      steps: Enum.reverse(state.done),
      guarantee: guarantee,
      policy: PolicySet.describe(state.policy_layers)
    }

    finish(
      state,
      :completed,
      {:ok, summary},
      :tx_completed,
      "COMPLETED via #{c.provider_id}: net #{Money.format(c.net)} at rate #{Money.format_rate(c.quote.rate)}"
    )
  end

  defp needs_review(state, reason, message) do
    finish(
      state,
      :needs_review,
      {:error, {:needs_review, reason}},
      :needs_review,
      "NEEDS REVIEW: #{message}"
    )
  end

  defp finish(state, phase, result, kind, message) do
    state =
      note(%{state | phase: phase, result: result}, kind, message, %{result: inspect(result)})

    emit_economics(state, phase)
    Enum.each(state.waiters, &GenServer.reply(&1, result))
    Process.send_after(self(), :linger_over, state.opts.linger_ms)
    %{state | waiters: []}
  end

  # ---- side effects ------------------------------------------------------

  defp do_select(state) do
    Selector.select(state.request, state.providers, state.policy,
      deadline_ms: state.opts.deadline_ms,
      baseline: state.opts.baseline
    )
  end

  defp run_step(state, op) do
    cond do
      deadline_passed?(state) and op == :settle ->
        needs_review(
          state,
          {:settlement_overdue, region(state)},
          "the #{region(state)} cut-off has passed before settlement was confirmed"
        )

      deadline_passed?(state) ->
        state =
          note(
            state,
            :deadline_missed,
            "the #{region(state)} cut-off has passed; stopping and undoing",
            %{
              region: region(state)
            }
          )

        undos = Enum.map(state.done, &{@undo[&1], &1})
        start_compensation(state, undos, {:abort, {:deadline_missed, region(state)}})

      true ->
        do_run_step(state, op)
    end
  end

  defp do_run_step(state, op) do
    c = state.current
    key = key(state, op)
    timeout = state.opts.step_timeout_ms
    args = if op == :settle, do: [key], else: [c.quote, key]

    state =
      note(
        sent(%{state | phase: @phase[op]}, args),
        :step_started,
        "asking #{c.provider_id} to #{@verb[op]} (attempt #{state.attempt}, key #{key})",
        %{provider: c.provider_id, op: op, key: key, attempt: state.attempt}
      )

    durably(state, fn state ->
      run(state, {:step, op}, fn -> Server.invoke(c.server, op, args, timeout) end)
    end)
  end

  # Write-ahead: the record of what we are about to ask must be on disk before we
  # ask. If it cannot be, we do not move money (fail closed).
  defp durably(state, send_fun) do
    case state.opts.barrier.() do
      :ok -> send_fun.(state)
      {:error, why} -> audit_unavailable(state, why)
    end
  end

  defp audit_unavailable(state, why) do
    message =
      "the durable log is unavailable (#{inspect(why)}); not moving money without a record"

    if state.done == [] and state.undo_queue == [] do
      finish(state, :failed, {:error, {:audit_unavailable, why}}, :tx_failed, message)
    else
      needs_review(state, {:audit_unavailable, why}, message)
    end
  end

  defp run_undo(state, undo) do
    c = state.current
    {^undo, original} = hd(state.undo_queue)
    key = key(state, original)
    timeout = state.opts.step_timeout_ms

    state =
      note(
        sent(%{state | compensations: state.compensations + 1}, [key]),
        :undo_started,
        "asking #{c.provider_id} to #{@verb[undo]} (key #{key})",
        %{provider: c.provider_id, op: undo, key: key}
      )

    durably(state, fn state ->
      run(state, {:undo, undo, original}, fn -> Server.invoke(c.server, undo, [key], timeout) end)
    end)
  end

  defp run_status(state, op) do
    c = state.current
    key = key(state, op)
    timeout = state.opts.step_timeout_ms

    state =
      note(
        sent(%{state | phase: :reconciling}, [key]),
        :status_check,
        "asking #{c.provider_id} what it holds for #{op}",
        %{
          provider: c.provider_id,
          op: op,
          key: key
        }
      )

    run(state, {:status, op}, fn -> Server.status(c.server, op, key, timeout) end)
  end

  defp run(state, tag, fun) do
    task =
      Task.async(fn ->
        started = System.monotonic_time(:millisecond)
        result = guarded(fun)
        {tag, result, System.monotonic_time(:millisecond) - started}
      end)

    # the selection round is counted per provider asked, not as one call
    calls = if tag == :selection, do: state.calls, else: state.calls + 1
    %{state | pending: task.ref, calls: calls}
  end

  # Journal: the raw answer to one provider call, as the orchestrator saw it.
  defp journal_response(_state, :selection, _result, _latency), do: :ok

  defp journal_response(state, tag, result, latency) do
    {kind, op, original} =
      case tag do
        {:step, op} -> {:step, op, op}
        {:status, op} -> {:status, op, op}
        {:undo, undo, original} -> {:undo, undo, original}
      end

    {class, reason} = classify_response(result)

    attempt =
      case kind do
        :step -> state.attempt
        :status -> state.reconcile_n
        :undo -> state.undo_attempt
      end

    Journal.record(
      state.id,
      :provider_response,
      "journal: #{state.current.provider_id} answered #{kind} #{op} (#{class})",
      %{
        provider: state.current.provider_id,
        kind: kind,
        op: op,
        key: key(state, original),
        attempt: attempt,
        class: class,
        reason: reason,
        latency_ms: latency
      }
    )
  end

  defp classify_response({:ok, _receipt}), do: {:ok, nil}
  defp classify_response({:error, reason}), do: {:error, inspect(reason)}
  defp classify_response({:rejected, reason}), do: {:rejected, inspect(reason)}
  # timeout, in_doubt, not_found... or something unexpected (kept, never crashing the transaction)
  defp classify_response(other),
    do: if(is_atom(other), do: {other, nil}, else: {:other, inspect(other)})

  defp asked_all(state), do: %{state | calls: state.calls + length(state.providers)}

  defp sent(state, term), do: %{state | bytes_out: state.bytes_out + :erlang.external_size(term)}

  # A provider server that has died must not take the transaction down with it:
  # treat it as a technical error and let retry / compensation / escalation decide.
  defp guarded(fun) do
    fun.()
  catch
    :exit, reason -> {:error, {:exit, reason}}
  end

  # What this transaction consumed and what it earned, as raw measurements.
  # `ActorTxSpike.Economics` prices them, so the rate card can change without
  # touching the orchestrator, and the audit log keeps the raw facts.
  defp emit_economics(state, outcome) do
    {:memory, memory} = Process.info(self(), :memory)
    now = System.system_time(:millisecond)

    Events.emit(state.id, :economics, :tx_economics, "measured #{state.id}", %{
      outcome: outcome,
      provider: state.current && state.current.provider_id,
      merchant: state.request[:merchant_id],
      amount: state.request[:amount],
      usage: %{
        reductions: reductions() - state.reductions0,
        memory_bytes: memory,
        duration_ms: now - state.started_ms,
        provider_calls: state.calls,
        bytes_out: state.bytes_out,
        bytes_in: state.bytes_in,
        state_changes: length(state.history),
        compensations: state.compensations
      },
      discovery: discovery(state, outcome),
      margin: margin(state, outcome),
      provider_fee: if(outcome == :completed, do: state.current.quote.fee, else: 0)
    })
  end

  # Price discovery: how the rate we actually got compares with the quotes we
  # could have had. The median is the reference ("what an unoptimised pick
  # would typically have cost"); the worst is the floor; the best shows what
  # non-price goals (speed, reliability) cost us.
  defp discovery(%{quote_nets: [_ | _] = nets, current: %{net: net}}, :completed) do
    sorted = Enum.sort(nets)

    %{
      quotes: length(nets),
      chosen_net: net,
      median_net: median(sorted),
      worst_net: hd(sorted),
      best_net: List.last(sorted)
    }
  end

  defp discovery(_state, _outcome), do: nil

  defp median(sorted) do
    n = length(sorted)
    mid = div(n, 2)

    if rem(n, 2) == 1,
      do: Enum.at(sorted, mid),
      else: Money.div_half_even(Enum.at(sorted, mid - 1) + Enum.at(sorted, mid), 2)
  end

  defp margin(%{result: {:ok, %{guarantee: %{margin: margin}}}}, :completed), do: margin
  defp margin(_state, _outcome), do: 0

  defp reductions, do: elem(:erlang.process_info(self(), :reductions), 1)

  defp announce_policy(%{policy_layers: []} = state), do: state

  defp announce_policy(state) do
    note(state, :policy_resolved, "routing policy: #{PolicySet.describe(state.policy_layers)}", %{
      layers: PolicySet.describe(state.policy_layers)
    })
  end

  defp announce_deadline(%{cutoff: nil} = state), do: state

  defp announce_deadline(state) do
    now = System.system_time(:millisecond)
    deadline = Cutoff.next_ms(state.cutoff, now)
    seconds = Float.round((deadline - now) / 1_000, 1)

    note(
      %{state | deadline_ms: deadline},
      :deadline_set,
      "#{state.cutoff.region} cut-off in #{seconds}s",
      %{region: state.cutoff.region, deadline_ms: deadline, seconds: seconds}
    )
  end

  defp deadline_passed?(%{deadline_ms: nil}), do: false

  defp deadline_passed?(%{deadline_ms: deadline}),
    do: System.system_time(:millisecond) >= deadline

  defp region(state), do: state.cutoff.region

  defp settle_guarantee(%{guarantee: nil} = state, _candidate), do: {state, nil}

  defp settle_guarantee(%{guarantee: %Guarantee{} = g} = state, candidate) do
    amount = state.request[:amount]
    result = Guarantee.evaluate(g, amount, candidate.net)

    try do
      Ledger.record(state.ledger, %{
        tx_id: state.id,
        merchant_id: g.merchant_id,
        period: g.period,
        amount: amount,
        guaranteed_net: result.guaranteed_net,
        achieved_net: result.achieved_net,
        margin: result.margin
      })
    catch
      :exit, _ -> :ok
    end

    {kind, word} =
      if result.margin >= 0,
        do: {:guarantee_margin, "margin"},
        else: {:guarantee_exposure, "EXPOSURE"}

    state =
      note(
        state,
        kind,
        "guarantee #{g.period} for #{g.merchant_id}: #{word} #{Money.format(result.margin)} " <>
          "(guaranteed #{Money.format(result.guaranteed_net)}, achieved #{Money.format(result.achieved_net)})",
        Map.merge(result, %{merchant_id: g.merchant_id, period: g.period})
      )

    {state, result}
  end

  defp key(state, op) do
    short = %{accept_quote: "accept", authorise: "authorise", pay: "pay", settle: "settle"}
    "#{state.id}:#{state.current.provider_id}:#{short[op]}"
  end

  defp note(state, kind, message, data) do
    Events.emit(state.id, :payment, kind, message, Map.put(data, :phase, state.phase))

    %{
      state
      | history: [{System.system_time(:millisecond), state.phase, kind, message} | state.history]
    }
  end
end

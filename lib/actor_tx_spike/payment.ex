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

  alias ActorTxSpike.{Events, Selector}
  alias ActorTxSpike.Provider.{Quote, Server}

  @undo %{accept_quote: :cancel_accept, authorise: :void_authorisation, pay: :refund}
  @phase %{accept_quote: :accepting, authorise: :authorising, pay: :paying}
  @next %{accept_quote: :authorise, authorise: :pay, pay: nil}
  @verb %{
    accept_quote: "accept the quote",
    authorise: "authorise the payment",
    pay: "make the payment",
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
    baseline: nil
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
    state = %{
      id: Keyword.fetch!(args, :id),
      request: Keyword.fetch!(args, :request),
      providers: Keyword.fetch!(args, :providers),
      policy: Keyword.fetch!(args, :policy),
      opts: Map.new(Keyword.merge(@defaults, Keyword.get(args, :opts, []))),
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
      result: nil
    }

    {:ok, state, {:continue, :start}}
  end

  @impl true
  def handle_continue(:start, state) do
    amount = state.request[:amount]

    state =
      note(state, :tx_received, "transaction received: amount #{amount}", %{amount: amount})

    state = %{state | phase: :quoting}
    {:noreply, run(state, :selection, fn -> do_select(state) end)}
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
       done: Enum.reverse(state.done),
       history: Enum.reverse(state.history),
       result: state.result
     }, state}
  end

  @impl true
  def handle_info({ref, {tag, result}}, %{pending: ref} = state) do
    Process.demonitor(ref, [:flush])
    {:noreply, handle_result(tag, result, %{state | pending: nil})}
  end

  def handle_info({:retry_step, op}, state), do: {:noreply, run_step(state, op)}
  def handle_info({:retry_undo, undo}, state), do: {:noreply, run_undo(state, undo)}
  def handle_info({:reconcile_again, op}, state), do: {:noreply, run_status(state, op)}
  def handle_info(:linger_over, state), do: {:stop, :normal, state}

  # ---- results -----------------------------------------------------------

  defp handle_result(:selection, %{outcome: {:selected, _}} = selection, state) do
    try_next_candidate(%{state | candidates: selection.ranked})
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

  defp handle_result({:status, :pay}, unresolved, state) do
    c = state.current

    if state.reconcile_n < state.opts.reconcile_attempts do
      state =
        note(
          state,
          :reconcile_pending,
          "#{c.provider_id} cannot yet confirm the payment (#{inspect(unresolved)}); " <>
            "asking again (#{state.reconcile_n}/#{state.opts.reconcile_attempts})",
          %{provider: c.provider_id}
        )

      Process.send_after(self(), {:reconcile_again, :pay}, state.opts.reconcile_interval_ms)
      %{state | reconcile_n: state.reconcile_n + 1}
    else
      needs_review(
        state,
        {:in_doubt, :pay},
        "payment with #{c.provider_id} is unresolved; NOT failing over (risk of double payment)"
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
            "going with #{c.provider_id} (quote rate #{c.quote.rate}, fee #{c.quote.fee})",
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

    summary = %{
      id: state.id,
      provider: c.provider_id,
      rate: c.quote.rate,
      fee: c.quote.fee,
      net: c.net,
      steps: Enum.reverse(state.done)
    }

    finish(
      state,
      :completed,
      {:ok, summary},
      :tx_completed,
      "COMPLETED via #{c.provider_id}: net #{Float.round(c.net * 1.0, 2)} at rate #{c.quote.rate}"
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
    c = state.current
    key = key(state, op)
    timeout = state.opts.step_timeout_ms

    state =
      note(
        %{state | phase: @phase[op]},
        :step_started,
        "asking #{c.provider_id} to #{@verb[op]} (attempt #{state.attempt}, key #{key})",
        %{provider: c.provider_id, op: op, key: key, attempt: state.attempt}
      )

    run(state, {:step, op}, fn -> Server.invoke(c.server, op, [c.quote, key], timeout) end)
  end

  defp run_undo(state, undo) do
    c = state.current
    {^undo, original} = hd(state.undo_queue)
    key = key(state, original)
    timeout = state.opts.step_timeout_ms

    state =
      note(state, :undo_started, "asking #{c.provider_id} to #{@verb[undo]} (key #{key})", %{
        provider: c.provider_id,
        op: undo,
        key: key
      })

    run(state, {:undo, undo, original}, fn -> Server.invoke(c.server, undo, [key], timeout) end)
  end

  defp run_status(state, op) do
    c = state.current
    key = key(state, op)
    timeout = state.opts.step_timeout_ms

    state =
      note(
        %{state | phase: :reconciling},
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
    task = Task.async(fn -> {tag, guarded(fun)} end)
    %{state | pending: task.ref}
  end

  # A provider server that has died must not take the transaction down with it:
  # treat it as a technical error and let retry / compensation / escalation decide.
  defp guarded(fun) do
    fun.()
  catch
    :exit, reason -> {:error, {:exit, reason}}
  end

  defp key(state, op) do
    short = %{accept_quote: "accept", authorise: "authorise", pay: "pay"}
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

defmodule ActorTxSpike.Demo.Bulk do
  @moduledoc """
  Sends many transactions into the demo world, one `Demo` submit at a time with a
  settable pause between them, and keeps score of how they end.

  One batch runs at a time. A batch is a `Bulk.Plan` (count, amount range, interval,
  fixed or randomised settings). Progress comes from the narrative: a transaction is
  counted once its payment announces how it ended.

  Back-pressure: if the system answers a submission with `{:error, {:overloaded, ms}}`
  (see `ActorTxSpike.Admission`), the same transaction is held and sent again after the
  pause it suggests, and counted as *throttled*; with a plan `max_in_flight` the sender also
  waits while that many of its own transactions are still running.

  Commands (`command/1`, the dashboard's `{"action": "bulk"}`):

    * `{"do": "start", ...plan parameters}`: begin a batch
    * `{"do": "stop"}`: stop submitting (transactions already sent finish normally)
    * `{"do": "clear"}`: forget a finished batch
  """
  use GenServer

  alias ActorTxSpike.{Demo, Events}
  alias ActorTxSpike.Demo.Bulk.Plan

  @terminal %{tx_completed: :completed, tx_failed: :failed, needs_review: :needs_review}
  @recent 12
  @poll_ms 5
  @reason_chars 110
  @reason_kinds 40

  def start_link(opts \\ []), do: GenServer.start_link(__MODULE__, opts, name: __MODULE__)

  @doc "Apply a dashboard command."
  @spec command(map()) :: {:ok, map()} | {:error, String.t()}
  def command(params), do: GenServer.call(__MODULE__, {:command, params})

  @doc "Progress, for the dashboard."
  @spec snapshot() :: map()
  def snapshot, do: GenServer.call(__MODULE__, :snapshot)

  # ---- server --------------------------------------------------------------

  @impl true
  def init(opts) do
    Events.subscribe()
    {:ok, idle(Keyword.get(opts, :submit, &Demo.control/1))}
  end

  defp idle(submit) do
    %{
      submit: submit,
      status: :idle,
      plan: nil,
      rand: nil,
      token: nil,
      held: nil,
      submitted: 0,
      errors: 0,
      throttled: 0,
      waited: 0,
      pending: MapSet.new(),
      outcomes: %{completed: 0, failed: 0, needs_review: 0},
      reasons: %{},
      recent: [],
      started_ms: nil,
      ended_ms: nil
    }
  end

  @impl true
  def handle_call({:command, %{"do" => "start"} = params}, _from, state),
    do: params |> Map.delete("do") |> start(state)

  def handle_call({:command, %{"do" => "stop"}}, _from, state), do: stop(state)

  def handle_call({:command, %{"do" => "clear"}}, _from, %{status: :running} = state),
    do: {:reply, {:error, "stop the batch first"}, state}

  def handle_call({:command, %{"do" => "clear"}}, _from, state),
    do: {:reply, {:ok, %{cleared: true}}, idle(state.submit)}

  def handle_call({:command, _other}, _from, state),
    do: {:reply, {:error, "unknown bulk command"}, state}

  def handle_call(:snapshot, _from, state), do: {:reply, view(state), state}

  @impl true
  def handle_info({:tick, token}, %{token: token, status: :running} = state),
    do: {:noreply, tick(state)}

  def handle_info({:tx_event, %{actor: :payment, tx_id: tx, kind: kind, message: message}}, state)
      when is_map_key(@terminal, kind),
      do:
        {:noreply, state |> settle(tx, Map.fetch!(@terminal, kind), message) |> finish_if_done()}

  def handle_info(_other, state), do: {:noreply, state}

  # ---- running a batch -----------------------------------------------------

  defp start(_params, %{status: :running} = state),
    do: {:reply, {:error, "a batch is already running: stop it first"}, state}

  defp start(params, state) do
    case Plan.new(params) do
      {:ok, plan} -> {:reply, {:ok, %{started: Plan.describe(plan)}}, begin(plan, state)}
      {:error, errors} -> {:reply, {:error, Enum.join(errors, "; ")}, state}
    end
  end

  defp begin(plan, state) do
    token = make_ref()
    send(self(), {:tick, token})

    %{
      idle(state.submit)
      | status: :running,
        plan: plan,
        rand: Plan.start(plan),
        token: token,
        started_ms: now()
    }
  end

  defp stop(%{status: :running} = state),
    do: {:reply, {:ok, %{stopped: true}}, finish(state, :stopped)}

  defp stop(state), do: {:reply, {:error, "no batch is running"}, state}

  # One step of the sender: wait if our own cap is reached, else send the next transaction.
  defp tick(state) do
    if at_cap?(state), do: wait(state), else: state |> attempt() |> proceed()
  end

  defp at_cap?(%{plan: %{max_in_flight: 0}}), do: false
  defp at_cap?(%{plan: %{max_in_flight: cap}, pending: pending}), do: MapSet.size(pending) >= cap

  defp wait(state), do: schedule(%{state | waited: state.waited + 1}, @poll_ms)

  defp schedule(state, ms) do
    Process.send_after(self(), {:tick, state.token}, ms)
    state
  end

  defp attempt(state) do
    {command, state} = next_command(state)
    outcome(state, command, state.submit.(command))
  end

  # A command the system refused as overloaded is held and sent again, not skipped.
  defp next_command(%{held: nil} = state) do
    {command, rand} = Plan.next(state.plan, state.rand)
    {command, %{state | rand: rand}}
  end

  defp next_command(%{held: held} = state), do: {held, %{state | held: nil}}

  defp outcome(state, command, {:error, {:overloaded, retry_ms}}),
    do: {:throttled, retry_ms, %{state | held: command, throttled: state.throttled + 1}}

  defp outcome(state, command, {:ok, id}) do
    entry = %{id: id, amount: command["amount"]}

    {:sent,
     %{
       state
       | submitted: state.submitted + 1,
         pending: MapSet.put(state.pending, id),
         recent: Enum.take([entry | state.recent], @recent)
     }}
  end

  defp outcome(state, _command, {:error, _reason}),
    do: {:sent, %{state | submitted: state.submitted + 1, errors: state.errors + 1}}

  defp proceed({:throttled, retry_ms, state}),
    do: schedule(state, max(retry_ms, state.plan.interval_ms))

  defp proceed({:sent, %{submitted: n, plan: %{count: n}} = state}), do: finish_if_done(state)
  defp proceed({:sent, state}), do: schedule(state, state.plan.interval_ms)

  defp settle(state, tx, outcome, message) do
    if MapSet.member?(state.pending, tx) do
      %{
        state
        | pending: MapSet.delete(state.pending, tx),
          outcomes: Map.update!(state.outcomes, outcome, &(&1 + 1)),
          reasons: why(state.reasons, outcome, message)
      }
    else
      state
    end
  end

  # Why transactions did not complete, with the numbers and the detail taken out so like ones group.
  defp why(reasons, :completed, _message), do: reasons

  defp why(reasons, _outcome, message) do
    key = message |> String.replace(~r/[0-9][0-9.]*/, "N") |> String.slice(0, @reason_chars)

    key =
      if Map.has_key?(reasons, key) or map_size(reasons) < @reason_kinds, do: key, else: "other"

    Map.update(reasons, key, 1, &(&1 + 1))
  end

  # All submitted and all ended: the batch is finished.
  defp finish_if_done(%{status: :running, plan: %{count: n}, submitted: n} = state) do
    if MapSet.size(state.pending) == 0, do: finish(state, :finished), else: state
  end

  defp finish_if_done(state), do: state

  defp finish(state, status), do: %{state | status: status, token: nil, ended_ms: now()}

  defp now, do: System.monotonic_time(:millisecond)

  # ---- what the dashboard sees ---------------------------------------------

  # Everything is sent but some transactions have not ended yet.
  defp label(%{status: :running, submitted: n, plan: %{count: n}}), do: :draining
  defp label(state), do: state.status

  defp view(%{plan: nil}), do: %{available: true, status: :idle}

  defp view(state) do
    elapsed = (state.ended_ms || now()) - state.started_ms

    Map.merge(state.outcomes, %{
      available: true,
      status: label(state),
      plan: Plan.describe(state.plan),
      count: state.plan.count,
      submitted: state.submitted,
      errors: state.errors,
      throttled: state.throttled,
      waited: state.waited,
      in_flight: MapSet.size(state.pending),
      elapsed_ms: elapsed,
      per_second: Float.round(state.submitted / max(elapsed, 1) * 1_000, 1),
      reasons: top_reasons(state.reasons),
      recent: state.recent
    })
  end

  defp top_reasons(reasons) do
    reasons
    |> Enum.sort_by(fn {_reason, n} -> -n end)
    |> Enum.take(8)
    |> Enum.map(fn {reason, n} -> %{reason: reason, count: n} end)
  end
end

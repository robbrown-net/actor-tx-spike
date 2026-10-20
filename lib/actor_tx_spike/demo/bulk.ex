defmodule ActorTxSpike.Demo.Bulk do
  @moduledoc """
  Sends many transactions into the demo world, one `Demo` submit at a time with a
  settable pause between them, and keeps score of how they end.

  One batch runs at a time. A batch is a `Bulk.Plan` (count, amount range, interval,
  fixed or randomised settings). Progress comes from the narrative: a transaction is
  counted once its payment announces how it ended.

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
      submitted: 0,
      errors: 0,
      pending: MapSet.new(),
      outcomes: %{completed: 0, failed: 0, needs_review: 0},
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
    do: {:noreply, state |> submit_one() |> after_submit()}

  def handle_info({:tx_event, %{actor: :payment, tx_id: tx, kind: kind}}, state)
      when is_map_key(@terminal, kind),
      do: {:noreply, state |> settle(tx, Map.fetch!(@terminal, kind)) |> finish_if_done()}

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

  defp submit_one(state) do
    {command, rand} = Plan.next(state.plan, state.rand)
    state = %{state | rand: rand, submitted: state.submitted + 1}
    record(state, command, state.submit.(command))
  end

  defp record(state, command, {:ok, id}) do
    entry = %{id: id, amount: command["amount"]}

    %{
      state
      | pending: MapSet.put(state.pending, id),
        recent: Enum.take([entry | state.recent], @recent)
    }
  end

  defp record(state, _command, {:error, _reason}), do: %{state | errors: state.errors + 1}

  defp after_submit(%{submitted: n, plan: %{count: n}} = state), do: finish_if_done(state)

  defp after_submit(state) do
    Process.send_after(self(), {:tick, state.token}, state.plan.interval_ms)
    state
  end

  defp settle(state, tx, outcome) do
    if MapSet.member?(state.pending, tx) do
      %{
        state
        | pending: MapSet.delete(state.pending, tx),
          outcomes: Map.update!(state.outcomes, outcome, &(&1 + 1))
      }
    else
      state
    end
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
      in_flight: MapSet.size(state.pending),
      elapsed_ms: elapsed,
      per_second: Float.round(state.submitted / max(elapsed, 1) * 1_000, 1),
      recent: state.recent
    })
  end
end

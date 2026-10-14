defmodule ActorTxSpike.Transaction do
  @moduledoc """
  Transaction state machine.

  States: `queued`, `awaiting_a`, `awaiting_b`, `compensating`, `committed`, `failed`.
  """
  @behaviour :gen_statem

  alias ActorTxSpike.{Store, Subsystem}

  @max_retries 5
  @backoff_ms 25

  def start_link(opts) do
    id = Keyword.fetch!(opts, :id)
    :gen_statem.start_link(via(id), __MODULE__, opts, [])
  end

  def via(id), do: {:via, Registry, {ActorTxSpike.Registry, {:tx, id}}}

  def await(id, timeout \\ 5_000) do
    :gen_statem.call(via(id), :await, timeout)
  catch
    :exit, {:noproc, _} -> from_store(id)
    :exit, {:normal, _} -> from_store(id)
  end

  def callback_mode, do: :handle_event_function

  def init(opts) do
    data =
      case Keyword.get(opts, :snapshot) do
        nil ->
          %{
            id: Keyword.fetch!(opts, :id),
            payload: Keyword.fetch!(opts, :payload),
            state: :queued,
            retries: 0,
            max_retries: @max_retries,
            result_a: nil,
            result_b: nil,
            last_error: nil,
            waiters: []
          }

        snapshot ->
          snapshot
          |> Map.new()
          |> Map.put(:waiters, [])
      end

    persist(data)
    {:ok, data.state, data, [{:next_event, :internal, :advance}]}
  end

  def handle_event({:call, from}, :await, state, data) when state in [:committed, :failed] do
    {:keep_state, data, [{:reply, from, terminal_reply(state, data)}]}
  end

  def handle_event({:call, from}, :await, _state, data) do
    {:keep_state, %{data | waiters: [from | data.waiters]}}
  end

  def handle_event(:internal, :advance, :queued, data) do
    request(:a, data)
    goto(%{data | state: :awaiting_a})
  end

  def handle_event(:internal, :advance, :awaiting_a, data) do
    request(:a, data)
    :keep_state_and_data
  end

  def handle_event(:internal, :advance, :awaiting_b, data) do
    request(:b, data)
    :keep_state_and_data
  end

  def handle_event(:internal, :advance, :compensating, data) do
    # Spike compensation: record that A would be undone, then fail.
    failed = %{data | state: :failed, last_error: data.last_error || :compensated}
    persist(failed)
    {:next_state, :failed, reply_waiters(failed)}
  end

  def handle_event(:internal, :advance, state, data) when state in [:committed, :failed] do
    {:keep_state, reply_waiters(data)}
  end

  def handle_event(:cast, {:result, :a, {:ok, result}}, :awaiting_a, data) do
    next = %{data | result_a: result, retries: 0, state: :awaiting_b}
    persist(next)
    request(:b, next)
    {:next_state, :awaiting_b, next}
  end

  def handle_event(:cast, {:result, :b, {:ok, result}}, :awaiting_b, data) do
    committed = %{data | result_b: result, retries: 0, state: :committed}
    persist(committed)
    {:next_state, :committed, reply_waiters(committed)}
  end

  def handle_event(:cast, {:result, _which, {:error, reason}}, state, data)
      when state in [:awaiting_a, :awaiting_b] do
    retries = data.retries + 1
    data = %{data | retries: retries, last_error: reason}

    if retries > data.max_retries do
      if state == :awaiting_b do
        next = %{data | state: :compensating}
        persist(next)
        {:next_state, :compensating, next, [{:next_event, :internal, :advance}]}
      else
        failed = %{data | state: :failed}
        persist(failed)
        {:next_state, :failed, reply_waiters(failed)}
      end
    else
      persist(data)
      {:keep_state, data, [{:state_timeout, @backoff_ms * retries, :retry}]}
    end
  end

  def handle_event(:state_timeout, :retry, state, data)
      when state in [:awaiting_a, :awaiting_b] do
    which = if state == :awaiting_a, do: :a, else: :b
    request(which, data)
    :keep_state_and_data
  end

  def handle_event(:cast, {:result, _which, _result}, _state, _data) do
    :keep_state_and_data
  end

  def handle_event(:info, {:DOWN, _ref, :process, _pid, _reason}, _state, _data) do
    :keep_state_and_data
  end

  defp request(which, data) do
    parent = self()
    name = if which == :a, do: :subsystem_a, else: :subsystem_b

    {:ok, _} =
      Task.start(fn ->
        result =
          try do
            Subsystem.process(name, data.id, data.payload)
          catch
            :exit, reason -> {:error, {:exit, reason}}
          end

        :gen_statem.cast(parent, {:result, which, result})
      end)

    :ok
  end

  defp goto(data) do
    persist(data)
    {:next_state, data.state, data}
  end

  defp persist(data) do
    Store.put(Map.drop(data, [:waiters]))
  end

  defp reply_waiters(data) do
    Enum.each(data.waiters, fn from ->
      :gen_statem.reply(from, terminal_reply(data.state, data))
    end)

    %{data | waiters: []}
  end

  defp terminal_reply(:committed, data), do: {:ok, Map.drop(data, [:waiters])}
  defp terminal_reply(:failed, data), do: {:error, Map.drop(data, [:waiters])}

  defp from_store(id) do
    case Store.get(id) do
      %{state: :committed} = snap -> {:ok, snap}
      %{state: :failed} = snap -> {:error, snap}
      nil -> {:error, :not_found}
      _snap -> {:error, :not_running}
    end
  end
end

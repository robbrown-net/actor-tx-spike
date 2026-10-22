defmodule ActorTxSpike.Review do
  @moduledoc """
  The review workflow: what a person does with a transaction the system would not settle by
  guessing (`needs_review`).

  A transaction goes to review when a payment could not be confirmed, a settlement or undo
  could not be completed, or the audit log failed after money moved. The system stops and the
  transaction waits here, open, until a person decides. A person can:

    * **check** with the provider: ask what it holds for each step's idempotency key
      (read-only, nothing changes);
    * **retry** a step that is safe to repeat under its original key: settle, or an undo
      (cancel the accept, void the authorisation, refund). Repeating it cannot repeat the effect;
    * **resolve** it: `paid` (the money moved and it is whole), `not_paid` (nothing moved), or
      `written_off` (it cannot be determined and the exposure is accepted), saying who and why.

  Everything a person does is written to the audit log *before* it takes effect (a retry is
  recorded, flushed, then sent; if the log cannot take the record, nothing is sent), in the same
  regional logs as the transaction. The queue is rebuilt from the logs after a restart with
  `load/1`. This server is started by the host, with a way to find the providers.
  """
  use GenServer

  alias ActorTxSpike.{Events, Payment}
  alias ActorTxSpike.Provider.Server
  alias ActorTxSpike.Review.Queue

  @retry_ops ~w(settle cancel_accept void_authorisation refund)
  @check_ops ~w(accept_quote authorise pay settle)
  @key_step %{
    "accept_quote" => "accept",
    "authorise" => "authorise",
    "pay" => "pay",
    "settle" => "settle",
    "cancel_accept" => "accept",
    "void_authorisation" => "authorise",
    "refund" => "pay"
  }
  @call_timeout 5_000

  def start_link(opts \\ []), do: GenServer.start_link(__MODULE__, opts, name: __MODULE__)

  @doc "Every entry, open ones first (oldest first), then resolved ones (latest first)."
  @spec queue() :: [map()]
  def queue, do: GenServer.call(__MODULE__, :queue)

  @doc "Counts and the entries, for the dashboard."
  @spec snapshot() :: map()
  def snapshot, do: GenServer.call(__MODULE__, :snapshot)

  @doc "Ask the provider what it holds for each step of a transaction (read-only)."
  @spec check(String.t(), String.t()) :: {:ok, [map()]} | {:error, String.t()}
  def check(tx, by), do: GenServer.call(__MODULE__, {:check, tx, by}, 30_000)

  @doc "Send a step again under its original key: settle, cancel_accept, void_authorisation or refund."
  @spec retry(String.t(), String.t(), String.t()) :: {:ok, map()} | {:error, String.t()}
  def retry(tx, op, by), do: GenServer.call(__MODULE__, {:retry, tx, op, by}, 30_000)

  @doc "Close a review with a decision: `paid`, `not_paid` or `written_off`, by someone, with a reason."
  @spec resolve(String.t(), String.t(), String.t(), String.t()) ::
          {:ok, map()} | {:error, String.t()}
  def resolve(tx, outcome, by, note),
    do: GenServer.call(__MODULE__, {:resolve, tx, outcome, by, note})

  @doc "Rebuild entries from audit-log records (all the days' records, string keys). Existing entries win."
  @spec load([map()]) :: :ok
  def load(records), do: GenServer.call(__MODULE__, {:load, records})

  @doc "Apply a dashboard command (`do`: check, retry, resolve)."
  @spec command(map()) :: {:ok, term()} | {:error, String.t()}
  def command(%{"do" => "check", "tx" => tx, "by" => by}), do: check(tx, by)
  def command(%{"do" => "retry", "tx" => tx, "op" => op, "by" => by}), do: retry(tx, op, by)

  def command(%{"do" => "resolve", "tx" => tx, "outcome" => outcome, "by" => by, "note" => note}),
    do: resolve(tx, outcome, by, note)

  def command(_other),
    do: {:error, "a review command needs do, tx and who is acting (check, retry, resolve)"}

  # ---- server --------------------------------------------------------------

  @impl true
  def init(opts) do
    Events.subscribe()

    {:ok,
     %{
       entries: %{},
       providers: Keyword.get(opts, :providers, fn -> [] end),
       barrier: Keyword.get(opts, :barrier, &Events.barrier/0)
     }}
  end

  @impl true
  def handle_call(:queue, _from, state), do: {:reply, ordered(state), state}

  def handle_call(:snapshot, _from, state) do
    entries = ordered(state)
    open = Enum.count(entries, &(&1.status == :open))

    {:reply, %{available: true, open: open, resolved: length(entries) - open, entries: entries},
     state}
  end

  def handle_call({:load, records}, _from, state) do
    loaded = records |> Queue.from_records() |> Map.new(&{&1.tx, &1})
    {:reply, :ok, %{state | entries: Map.merge(loaded, state.entries)}}
  end

  def handle_call({:check, tx, by}, _from, state),
    do: act(state, tx, by, fn entry, server -> checked(state, entry, server, by) end)

  def handle_call({:retry, tx, op, by}, _from, state),
    do: act(state, tx, by, fn entry, server -> retried(state, entry, server, op, by) end)

  def handle_call({:resolve, tx, outcome, by, note}, _from, state) do
    with {:ok, entry} <- open_entry(state, tx),
         :ok <- Queue.check_resolution(outcome, by, note),
         :ok <-
           durably(state, tx, :review_resolved, "#{by} resolved #{tx} as #{outcome}", %{
             by: by,
             outcome: outcome,
             note: note
           }) do
      resolved = Queue.resolve(entry, now(), outcome, by, note)
      {:reply, {:ok, resolved.resolution}, put(state, resolved)}
    else
      {:error, _message} = error -> {:reply, error, state}
    end
  end

  @impl true
  def handle_info(
        {:tx_event,
         %{kind: :needs_review, tx_id: tx, message: message, at_ms: at, actor: :payment}},
        state
      ),
      do: {:noreply, open_entry_for(state, tx, message, at)}

  def handle_info(_other, state), do: {:noreply, state}

  # ---- opening -------------------------------------------------------------

  defp open_entry_for(state, tx, message, at) do
    if Map.has_key?(state.entries, tx),
      do: state,
      else: put(state, Queue.open(tx, at, message, details(tx)))
  end

  # What the transaction itself knows, while its process is still alive.
  defp details(tx) do
    case Payment.snapshot(tx) do
      nil ->
        %{}

      snap ->
        %{
          provider: snap.provider && to_string(snap.provider),
          done: Enum.map(snap.done, &to_string/1),
          story: story(snap)
        }
    end
  end

  defp story(snap),
    do:
      snap.history |> Enum.map(fn {_at, _phase, _kind, message} -> message end) |> Enum.take(-30)

  # ---- acting --------------------------------------------------------------

  # Every action needs an open entry, someone to blame, and the provider the transaction used.
  defp act(state, tx, by, fun) do
    with {:ok, entry} <- open_entry(state, tx),
         :ok <- named(by),
         {:ok, server} <- provider_server(state, entry) do
      fun.(entry, server)
    else
      {:error, _message} = error -> {:reply, error, state}
    end
  end

  defp checked(state, entry, server, by) do
    answers = Enum.map(@check_ops, &answer(entry, server, &1))

    case durably(
           state,
           entry.tx,
           :review_checked,
           "#{by} checked #{entry.tx} with #{entry.provider}",
           %{by: by, answers: answers}
         ) do
      :ok ->
        {:reply, {:ok, answers},
         put(state, Queue.add_action(entry, now(), "check", by, nil, answers))}

      {:error, _} = error ->
        {:reply, error, state}
    end
  end

  defp answer(entry, server, op) do
    key = key(entry, op)
    %{op: op, key: key, answer: inspect(Server.status(server, String.to_existing_atom(op), key))}
  end

  defp retried(state, entry, server, op, by) when op in @retry_ops do
    key = key(entry, op)
    intent = %{by: by, op: op, key: key}
    message = "#{by} asks to send #{op} again to #{entry.provider} (key #{key})"

    case durably(state, entry.tx, :review_retry, message, intent) do
      :ok -> send_again(state, entry, server, intent)
      {:error, _} = error -> {:reply, error, state}
    end
  end

  defp retried(state, _entry, _server, _op, _by),
    do: {:reply, {:error, "only these can be sent again: #{Enum.join(@retry_ops, ", ")}"}, state}

  # Only after the intent is durable is the step actually sent.
  defp send_again(state, entry, server, %{by: by, op: op, key: key} = intent) do
    result = inspect(Server.invoke(server, String.to_existing_atom(op), [key], @call_timeout))
    message = "#{op} at #{entry.provider} answered #{result}"
    Events.emit(entry.tx, :review, :review_result, message, Map.put(intent, :result, result))

    entry =
      entry
      |> Queue.add_action(now(), "retry", by, op, "requested")
      |> Queue.add_action(now(), "result", by, op, result)

    {:reply, {:ok, %{op: op, key: key, result: result}}, put(state, entry)}
  end

  # ---- helpers -------------------------------------------------------------

  defp open_entry(state, tx) do
    case Map.fetch(state.entries, tx) do
      {:ok, %{status: :open} = entry} -> {:ok, entry}
      {:ok, _resolved} -> {:error, "#{tx} has already been resolved"}
      :error -> {:error, "#{tx} is not waiting for review"}
    end
  end

  defp named(by) when is_binary(by) and by != "", do: :ok

  defp named(_by),
    do: {:error, "say who is acting: everything a reviewer does is recorded under their name"}

  defp provider_server(_state, %{provider: nil}),
    do: {:error, "no provider was chosen for this transaction"}

  defp provider_server(state, %{provider: provider}) do
    case Enum.find(state.providers.(), &(to_string(&1.id) == provider)) do
      nil -> {:error, "provider #{provider} is not available"}
      %{server: server} -> {:ok, server}
    end
  end

  defp key(entry, op), do: "#{entry.tx}:#{entry.provider}:#{Map.fetch!(@key_step, op)}"

  # Record first and make it durable, then act: if the log cannot take it, nothing happens.
  defp durably(state, tx, kind, message, data) do
    Events.emit(tx, :review, kind, message, data)

    case state.barrier.() do
      :ok -> :ok
      {:error, _reason} -> {:error, "the audit log is unavailable, so nothing was done"}
    end
  end

  defp put(state, entry), do: %{state | entries: Map.put(state.entries, entry.tx, entry)}

  defp ordered(state) do
    {open, resolved} = state.entries |> Map.values() |> Enum.split_with(&(&1.status == :open))
    Enum.sort_by(open, & &1.opened_at_ms) ++ Enum.sort_by(resolved, &(-&1.resolution.at_ms))
  end

  defp now, do: System.system_time(:millisecond)
end

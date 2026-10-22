defmodule ActorTxSpike.Review.Queue do
  @moduledoc """
  The review queue as pure data: one entry per transaction that ended in `needs_review`,
  open until a person resolves it, with the trail of what people did.

  An entry is built either from a live transaction (`open/4`) or from audit-log records
  (`from_records/1`), and both give the same shape, so the queue can be rebuilt after a
  restart from the log alone. People's actions are logged as `review_checked`,
  `review_retry`, `review_result` and `review_resolved`; `from_records/1` folds them back in.

  An entry: `%{tx, status: :open | :resolved, opened_at_ms, reason, provider, done, request,
  story, actions, resolution}`. An action is `%{at_ms, kind, by, op, detail}`; a resolution is
  `%{outcome, by, note, at_ms}`.
  """

  alias ActorTxSpike.Replay
  alias ActorTxSpike.Replay.Tx

  @outcomes ~w(paid not_paid written_off)
  @story_lines 30
  @review_kinds ~w(review_checked review_retry review_result review_resolved)

  @doc "The ways a person can close a review."
  @spec outcomes() :: [String.t()]
  def outcomes, do: @outcomes

  @doc "A new open entry."
  @spec open(String.t(), integer(), String.t(), map()) :: map()
  def open(tx, at_ms, reason, details) do
    %{
      tx: tx,
      status: :open,
      opened_at_ms: at_ms,
      reason: reason,
      provider: details[:provider],
      done: details[:done] || [],
      request: details[:request],
      story: details[:story] || [],
      actions: [],
      resolution: nil
    }
  end

  @doc "Add something a person did to the trail."
  @spec add_action(map(), integer(), String.t(), String.t(), String.t() | nil, term()) :: map()
  def add_action(entry, at_ms, kind, by, op, detail),
    do: %{
      entry
      | actions: entry.actions ++ [%{at_ms: at_ms, kind: kind, by: by, op: op, detail: detail}]
    }

  @doc "Close the entry with a decision."
  @spec resolve(map(), integer(), String.t(), String.t(), String.t()) :: map()
  def resolve(entry, at_ms, outcome, by, note),
    do: %{
      entry
      | status: :resolved,
        resolution: %{outcome: outcome, by: by, note: note, at_ms: at_ms}
    }

  @doc "Is this a decision the log can stand behind (a known outcome, who decided, and why)?"
  @spec check_resolution(term(), term(), term()) :: :ok | {:error, String.t()}
  def check_resolution(outcome, by, note) do
    with :ok <- known(outcome),
         :ok <- given(by, "say who is deciding") do
      given(note, "say why: a decision without a reason cannot be audited")
    end
  end

  defp known(outcome) when outcome in @outcomes, do: :ok
  defp known(_other), do: {:error, "choose one of: #{Enum.join(@outcomes, ", ")}"}

  defp given(text, message) when is_binary(text),
    do: if(String.trim(text) == "", do: {:error, message}, else: :ok)

  defp given(_other, message), do: {:error, message}

  @doc """
  The queue as recorded in audit-log records (one or several logs' worth), oldest first.
  Entries are rebuilt from each transaction's records and the review actions after them.
  """
  @spec from_records([map()]) :: [map()]
  def from_records(records) do
    day = Replay.from_records(records)

    for id <- day.tx_order,
        rs = day.by_tx[id],
        needs = Tx.first(rs, "needs_review"),
        do: entry(id, rs, needs)
  end

  defp entry(id, rs, needs) do
    summary = Tx.summarize(rs)

    details = %{
      provider: summary.provider,
      done: summary.steps,
      request: summary.request,
      story: story(rs)
    }

    id
    |> open(needs["at"], needs["message"], details)
    |> fold(Enum.filter(rs, &(&1["kind"] in @review_kinds)))
  end

  defp story(rs) do
    rs
    |> Enum.reject(&(&1["actor"] in ["journal", "review"]))
    |> Enum.map(& &1["message"])
    |> Enum.take(-@story_lines)
  end

  @doc "Apply review records (string keys, as read from the log) to an entry."
  @spec fold(map(), [map()]) :: map()
  def fold(entry, records), do: Enum.reduce(records, entry, &apply_record/2)

  defp apply_record(%{"kind" => "review_checked", "data" => d, "at" => at}, entry),
    do: add_action(entry, at, "check", d["by"], nil, d["answers"])

  defp apply_record(%{"kind" => "review_retry", "data" => d, "at" => at}, entry),
    do: add_action(entry, at, "retry", d["by"], d["op"], "requested")

  defp apply_record(%{"kind" => "review_result", "data" => d, "at" => at}, entry),
    do: add_action(entry, at, "result", d["by"], d["op"], d["result"])

  defp apply_record(%{"kind" => "review_resolved", "data" => d, "at" => at}, entry),
    do: resolve(entry, at, d["outcome"], d["by"], d["note"])
end

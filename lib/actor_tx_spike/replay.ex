defmodule ActorTxSpike.Replay do
  @moduledoc """
  Replay the day from its log.

  The audit log (`ActorTxSpike.Audit`) holds the narrative *and* the journal
  (`ActorTxSpike.Journal`): every input from the outside world and enough
  context to judge every decision again. This module loads a day and answers
  four questions about it:

    * **What were the exact conditions at a moment?** `state_at/2`
    * **Did the day follow the rules?** `ActorTxSpike.Replay.Rules.verify/2`
    * **How does another system's day compare?** `ActorTxSpike.Replay.Compare.diff/2`
    * **What would a different strategy have done on this very day?**
      `ActorTxSpike.Replay.WhatIf.run/3`

  Decisions are not re-implemented for replay: the selection rule is the same
  pure function the live system used (`ActorTxSpike.Selector.evaluate/3`),
  fed with the recorded inputs.

  Records are the decoded JSON of the log (string keys), so a log from any
  other system that writes this format can be loaded, verified and compared.
  Loading verifies the hash chain first and refuses a log that fails it.
  """

  alias ActorTxSpike.{Audit, Replay.Tx}

  defmodule Day do
    @moduledoc "A loaded day: records in order, grouped by transaction."
    defstruct records: [], by_tx: %{}, tx_order: [], head: nil
    @type t :: %__MODULE__{}
  end

  @terminal ~w(tx_completed tx_failed needs_review)
  @position_keys %{at_ms: "at", seq: "seq", n: "n"}

  @doc "Load a day from an audit log file. Verifies the chain unless `verify: false`."
  @spec load(Path.t(), keyword()) :: {:ok, Day.t()} | {:error, term()}
  def load(path, opts \\ []) do
    with {:ok, head} <- check_chain(path, Keyword.get(opts, :verify, true)),
         {:ok, records} <- read(path) do
      {:ok, from_records(records, head)}
    end
  end

  @doc """
  Load every regional log in a directory (`<dir>/*.log`), keyed by log name.
  Refuses if any of them fails verification.
  """
  @spec load_dir(Path.t(), keyword()) :: {:ok, %{String.t() => Day.t()}} | {:error, term()}
  def load_dir(dir, opts \\ []) do
    dir
    |> Path.join("*.log")
    |> Path.wildcard()
    |> Enum.reduce_while({:ok, %{}}, fn path, {:ok, acc} ->
      case load(path, opts) do
        {:ok, day} -> {:cont, {:ok, Map.put(acc, Path.basename(path, ".log"), day)}}
        {:error, reason} -> {:halt, {:error, {Path.basename(path, ".log"), reason}}}
      end
    end)
  end

  @doc "Build a day from already-decoded records (tests, other sources)."
  @spec from_records([map()], map() | nil) :: Day.t()
  def from_records(records, head \\ nil) do
    by_tx = records |> Enum.reject(&is_nil(&1["tx"])) |> Enum.group_by(& &1["tx"])

    order =
      records
      |> Enum.filter(&(&1["kind"] == "tx_received"))
      |> Enum.map(& &1["tx"])
      |> Enum.uniq()

    %Day{records: records, by_tx: by_tx, tx_order: order, head: head}
  end

  @doc "A one-line-per-field summary of one transaction."
  @spec tx(Day.t(), String.t()) :: map() | nil
  def tx(%Day{by_tx: by_tx}, id), do: if(by_tx[id], do: Tx.summarize(by_tx[id]))

  @doc "Summaries of every transaction, in arrival order."
  @spec transactions(Day.t()) :: [map()]
  def transactions(%Day{} = day), do: Enum.map(day.tx_order, &tx(day, &1))

  @doc """
  The system as it stood at a moment: `{:at_ms, ms}`, `{:seq, n}` (event
  sequence) or `{:n, n}` (audit record number). Folds only the records up to
  and including that point.
  """
  @spec state_at(Day.t(), {:at_ms | :seq | :n, integer()}) :: map()
  def state_at(%Day{} = day, {field, value}) do
    upto = Enum.take_while(day.records, &(&1[@position_keys[field]] <= value))
    txs = summaries(upto)

    %{
      as_of: %{field => value, last_n: last(upto, "n"), last_at_ms: last(upto, "at")},
      transactions: txs,
      in_flight: for({id, %{outcome: :open}} <- txs, do: id) |> Enum.sort(),
      outcomes: Enum.frequencies_by(Map.values(txs), & &1.outcome),
      providers: providers(upto),
      policy: latest_data(upto, "tx_received", "policy"),
      guarantee: latest_data(upto, "tx_received", "guarantee"),
      config: latest_data(upto, "tx_received", "config"),
      controls: controls(upto),
      economics: economics(upto)
    }
  end

  # Every transaction that had started by the end of `records`, summarised as of then.
  defp summaries(records) do
    day = from_records(records)
    Map.new(day.tx_order, &{&1, Tx.summarize(day.by_tx[&1])})
  end

  defp controls(records) do
    for r <- records, r["kind"] == "control", do: %{at: r["at"], command: r["data"]["command"]}
  end

  @doc "Terminal event kinds."
  def terminal_kinds, do: @terminal

  # ---- internals ---------------------------------------------------------

  defp check_chain(_path, false), do: {:ok, nil}

  defp check_chain(path, true) do
    case Audit.verify(path) do
      {:ok, head} -> {:ok, head}
      {:error, reason} -> {:error, {:integrity, reason}}
    end
  end

  defp read(path) do
    if File.exists?(path), do: decode(path), else: {:error, :no_such_log}
  end

  # Even with verification off, a log that is not JSON is an error, not a crash.
  defp decode(path) do
    path
    |> File.stream!()
    |> Enum.reduce_while({:ok, []}, fn line, {:ok, acc} ->
      case JSON.decode(line) do
        {:ok, record} -> {:cont, {:ok, [record | acc]}}
        {:error, _} -> {:halt, {:error, :malformed_log}}
      end
    end)
    |> then(fn
      {:ok, records} -> {:ok, Enum.reverse(records)}
      error -> error
    end)
  end

  defp last([], _key), do: nil
  defp last(records, key), do: records |> List.last() |> Map.get(key)

  defp latest_data(records, kind, field) do
    records
    |> Enum.filter(&(&1["kind"] == kind))
    |> List.last()
    |> case do
      nil -> nil
      r -> r["data"][field]
    end
  end

  # Last known health and last observed price of every provider.
  defp providers(records) do
    health =
      for %{"kind" => "health", "data" => d} <- records, reduce: %{} do
        acc ->
          Map.put(
            acc,
            d["provider"],
            Map.take(d, ~w(state breaker error_rate p95_ms version recovering))
          )
      end

    quotes =
      for %{"kind" => "quote_observed", "data" => d, "at" => at} <- records, reduce: %{} do
        acc ->
          Map.put(
            acc,
            d["provider"],
            Map.merge(Map.take(d, ~w(outcome rate fee latency_ms state)), %{"at" => at})
          )
      end

    for id <- Enum.sort(Enum.uniq(Map.keys(health) ++ Map.keys(quotes))), into: %{} do
      {id, %{health: health[id], last_quote: quotes[id]}}
    end
  end

  defp economics(records) do
    for %{"kind" => "tx_costed", "data" => %{"value" => v, "cost" => c}} <- records,
        reduce: %{transactions: 0, saved_vs_median: 0, margin: 0, cost_picos: 0} do
      acc ->
        %{
          transactions: acc.transactions + 1,
          saved_vs_median: acc.saved_vs_median + v["saved_vs_median"],
          margin: acc.margin + v["margin"],
          cost_picos: acc.cost_picos + c["total"]
        }
    end
  end
end

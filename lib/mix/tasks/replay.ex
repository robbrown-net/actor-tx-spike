defmodule Mix.Tasks.Replay do
  @shortdoc "Replay a day from its audit log"
  @moduledoc """
  Replays a day recorded in an audit log (`mix demo --audit PATH`).

      mix replay verify  PATH                 # did the day follow the rules?
      mix replay txs     PATH                 # one line per transaction
      mix replay at      PATH --n 300         # the system at audit record 300
      mix replay at      PATH --ms 1792273775122
      mix replay whatif  PATH --set max_bps_from_best=25 --set 'weights={"cost":1}'
      mix replay diff    PATH_A PATH_B        # compare two systems' days
      mix replay reviews PATH                 # transactions that went to review: open, or who decided what and why
      mix replay batches PATH                 # the bulk batches sent, with their plans and results
      mix replay residency DIR                # does every transaction sit in exactly the logs it must?

  The log's hash chain is verified before anything else; a log that fails is refused.
  `verify` exits non-zero if any rule was broken.
  """
  use Mix.Task

  alias ActorTxSpike.{Replay, Review}
  alias ActorTxSpike.Replay.Report

  @positions [n: :n, ms: :at_ms, seq: :seq]

  @impl true
  def run(args) do
    {opts, rest, _} =
      OptionParser.parse(args, strict: [n: :integer, ms: :integer, seq: :integer, set: :keep])

    command(rest, opts)
  end

  defp command(["verify", path], _opts), do: path |> load() |> verify()
  defp command(["txs", path], _opts), do: path |> load() |> txs()
  defp command(["at", path], opts), do: path |> load() |> at(opts)

  defp command(["whatif", path], opts),
    do: path |> load() |> whatif(Keyword.get_values(opts, :set))

  defp command(["residency", dir], _opts), do: residency(dir)

  defp command(["reviews", path], _opts),
    do: path |> load() |> reviews() |> Mix.shell().info()

  defp command(["batches", path], _opts),
    do: path |> load() |> Replay.batches() |> Report.batches() |> Mix.shell().info()

  defp command(["diff", a, b], _opts),
    do: Mix.shell().info(Report.diff(Replay.Compare.diff(load(a), load(b))))

  defp command(_other, _opts) do
    Mix.raise(
      "usage: mix replay verify|txs|at|whatif PATH  |  mix replay diff A B  |  mix replay residency DIR"
    )
  end

  defp load(path) do
    case Replay.load(path) do
      {:ok, day} -> day
      {:error, why} -> Mix.raise("cannot replay #{path}: #{inspect(why)}")
    end
  end

  # Every transaction that went to review, open or resolved, with who decided what and why.
  defp reviews(day) do
    case Review.Queue.from_records(day.records) do
      [] -> "no transaction went to review"
      entries -> Enum.map_join(entries, "\n", &review_line/1)
    end
  end

  defp review_line(%{status: :open} = e), do: "OPEN     #{e.tx}  #{e.reason}"

  defp review_line(e) do
    r = e.resolution
    "RESOLVED #{e.tx}  #{r.outcome} by #{r.by}: #{r.note}  (was: #{e.reason})"
  end

  defp residency(dir) do
    days = load_dir(dir)
    result = Replay.Residency.check(days)

    Mix.shell().info(
      "#{result.transactions} transactions across #{map_size(days)} logs: " <>
        if(result.ok?, do: "RESIDENCY HELD", else: "#{length(result.violations)} VIOLATION(S)")
    )

    for v <- result.violations, do: Mix.shell().info("  [#{v.rule}] #{v.detail}")
    unless result.ok?, do: Mix.raise("data residency was not respected")
  end

  defp load_dir(dir) do
    case Replay.load_dir(dir) do
      {:ok, days} -> days
      {:error, why} -> Mix.raise("cannot replay #{dir}: #{inspect(why)}")
    end
  end

  defp verify(day) do
    result = Replay.Rules.verify(day)
    Mix.shell().info(Report.verify(result))
    unless result.ok?, do: Mix.raise("the day broke the rules")
  end

  defp txs(day) do
    for t <- Replay.transactions(day) do
      Mix.shell().info(
        "#{t.id}  #{t.outcome}  via #{t.provider || "-"}  amount #{t.amount}  net #{inspect(t.net)}  steps #{Enum.join(t.steps, ",")}"
      )
    end
  end

  defp at(day, opts), do: Mix.shell().info(Report.state(Replay.state_at(day, position(opts))))

  defp position(opts) do
    Enum.find_value(@positions, fn {option, kind} -> opts[option] && {kind, opts[option]} end) ||
      Mix.raise("give --n, --ms or --seq")
  end

  defp whatif(day, sets) do
    overrides = Enum.reduce(sets, %{}, &put_override/2)

    case Replay.WhatIf.run(day, overrides) do
      {:ok, result} -> Mix.shell().info(Report.what_if(result))
      {:error, errors} -> Mix.raise("not a valid policy change: #{Enum.join(errors, "; ")}")
    end
  end

  # "max_bps_from_best=25" or "weights={\"cost\":1}": the value is JSON, the key replaces the recorded one
  defp put_override(setting, acc) do
    [key, raw] = String.split(setting, "=", parts: 2)

    value =
      case JSON.decode(raw) do
        {:ok, v} -> v
        _ -> raw
      end

    Map.put(acc, key, value)
  end
end

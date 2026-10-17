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

  The log's hash chain is verified before anything else; a log that fails is refused.
  `verify` exits non-zero if any rule was broken.
  """
  use Mix.Task

  alias ActorTxSpike.Replay
  alias ActorTxSpike.Replay.Report

  @impl true
  def run(args) do
    {opts, rest, _} =
      OptionParser.parse(args, strict: [n: :integer, ms: :integer, seq: :integer, set: :keep])

    case rest do
      ["verify", path] -> verify(load(path))
      ["txs", path] -> txs(load(path))
      ["at", path] -> at(load(path), opts)
      ["whatif", path] -> whatif(load(path), Keyword.get_values(opts, :set))
      ["diff", a, b] -> Mix.shell().info(Report.diff(Replay.Compare.diff(load(a), load(b))))
      _ -> Mix.raise("usage: mix replay verify|txs|at|whatif PATH  |  mix replay diff A B")
    end
  end

  defp load(path) do
    case Replay.load(path) do
      {:ok, day} -> day
      {:error, why} -> Mix.raise("cannot replay #{path}: #{inspect(why)}")
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

  defp at(day, opts) do
    spec =
      cond do
        opts[:n] -> {:n, opts[:n]}
        opts[:ms] -> {:at_ms, opts[:ms]}
        opts[:seq] -> {:seq, opts[:seq]}
        true -> Mix.raise("give --n, --ms or --seq")
      end

    Mix.shell().info(Report.state(Replay.state_at(day, spec)))
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

defmodule Mix.Tasks.Load do
  @shortdoc "Measure how the system behaves under a burst of transactions"
  @moduledoc """
  Sends a batch of transactions into a fresh demo world (no dashboard) and reports how
  they ended, once for each admission limit given. Use it to choose a limit.

      mix load                                  # 1,000 transactions, no pause, no limit
      mix load --count 5000 --pace 0.2
      mix load --admission none,400,200,100,50  # one run per limit
      mix load --admission 100 --sender-cap 80  # the sender also waits for 80 in flight
      mix load --mode random --seed 7           # randomised settings (includes refusals)

  Options: `--count N` (default 1000), `--interval MS` pause between submissions (default 0),
  `--admission LIST` (`none` or numbers, comma separated), `--sender-cap N` (the sender's own
  limit on its in-flight transactions, default none), `--pace F` provider speed (default 0.2,
  smaller is faster), `--mode fixed|random` (default fixed: spot, business, no region, so
  nothing is refused by jurisdiction), `--seed N`, `--timeout SECONDS` per run (default 180).
  """
  use Mix.Task

  alias ActorTxSpike.{Admission, Demo, Poll}
  alias ActorTxSpike.Demo.Bulk

  @switches [
    count: :integer,
    interval: :integer,
    admission: :string,
    sender_cap: :integer,
    pace: :float,
    mode: :string,
    seed: :integer,
    timeout: :integer
  ]

  @impl true
  def run(args) do
    {opts, _, _} = OptionParser.parse(args, strict: @switches)
    Mix.Task.run("app.start")
    {:ok, _} = Demo.start_link([])
    {:ok, _} = Demo.control(%{"action" => "pace", "factor" => Keyword.get(opts, :pace, 0.2)})
    {:ok, _} = Bulk.start_link()

    limits = opts |> Keyword.get(:admission, "none") |> String.split(",", trim: true)
    Mix.shell().info(header())
    results = Enum.map(limits, &measure(&1, opts))
    Enum.each(results, &print/1)
    Enum.each(results, &print_reasons/1)
  end

  defp measure(limit, opts) do
    {:ok, _} = Demo.control(%{"action" => "admission", "max_in_flight" => limit})
    {:ok, _} = Bulk.command(%{"do" => "clear"})
    :ok = Admission.reset()
    {:ok, _} = Bulk.command(Map.put(plan(opts), "do", "start"))
    snapshot = wait(Keyword.get(opts, :timeout, 180))
    {limit, snapshot, Admission.stats()}
  end

  defp plan(opts) do
    %{
      "count" => Keyword.get(opts, :count, 1_000),
      "interval_ms" => Keyword.get(opts, :interval, 0),
      "max_in_flight" => Keyword.get(opts, :sender_cap, 0),
      "mode" => Keyword.get(opts, :mode, "fixed"),
      "seed" => Keyword.get(opts, :seed),
      "min_amount" => "1",
      "max_amount" => "1000",
      "fixed" => %{"instrument" => "spot", "customer_type" => "business"}
    }
  end

  defp wait(seconds) do
    finished? = fn -> Bulk.snapshot().status == :finished end

    unless Poll.until(finished?, tries: seconds * 5, every_ms: 200),
      do: Mix.raise("a run did not finish in time")

    Bulk.snapshot()
  end

  defp header do
    "limit  sent  completed  failed  review  throttled  rejected  peak  seconds  ended/s"
  end

  defp print({limit, s, admission}) do
    ended = s.completed + s.failed + s.needs_review
    seconds = Float.round(s.elapsed_ms / 1_000, 1)

    Mix.shell().info(
      [
        limit,
        s.submitted,
        s.completed,
        s.failed,
        s.needs_review,
        s.throttled,
        admission.rejected,
        admission.peak,
        seconds,
        Float.round(ended / max(seconds, 0.1), 1)
      ]
      |> Enum.zip([6, 5, 10, 7, 7, 10, 9, 5, 8, 8])
      |> Enum.map_join("  ", fn {value, width} ->
        value |> to_string() |> String.pad_leading(width)
      end)
    )
  end

  defp print_reasons({limit, s, _admission}) do
    Mix.shell().info("\nwhy transactions did not complete, admission #{limit}:")

    for %{reason: reason, count: n} <- s.reasons,
        do: Mix.shell().info("  #{String.pad_leading(to_string(n), 5)}  #{reason}")
  end
end

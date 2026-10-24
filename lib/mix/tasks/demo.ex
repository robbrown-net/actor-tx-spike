defmodule Mix.Tasks.Demo do
  @shortdoc "Run the live demo world and dashboard"
  @moduledoc """
  Starts the system with five fake FX providers, serves the dashboard and
  streams the narrative to the terminal.

      mix demo                 # dashboard on http://127.0.0.1:4040
      mix demo --port 4545
      mix demo --auto          # start with automatic traffic
      mix demo --quiet         # do not print the narrative to the terminal
      mix demo --pace 0.05     # scale provider latencies (1.0 = human-watchable)
      mix demo --sentinel-ms 500   # probe interval
      mix demo --audit audit/demo.log   # also keep the tamper-evident audit log
      mix demo --audit-dir audit/       # one log per region (data residency): GLOBAL, IN, CN
      mix demo --max-in-flight 100      # back-pressure: refuse submissions beyond 100 running
      mix demo --lab                    # the Test lab tab: supervised regional logs in a temp
                                        # directory, fault injection, crash/recover, scenarios
      mix demo --perf-history FILE      # the release history for the Performance tab (default
                                        # "Performance Analysis/results.json")
  """
  use Mix.Task

  alias ActorTxSpike.Audit.Router
  alias ActorTxSpike.Demo
  alias ActorTxSpike.Panel
  alias ActorTxSpike.Replay

  @switches [
    port: :integer,
    auto: :boolean,
    quiet: :boolean,
    pace: :float,
    sentinel_ms: :integer,
    audit: :string,
    audit_dir: :string,
    lab: :boolean,
    max_in_flight: :integer,
    perf_history: :string
  ]

  @impl true
  def run(args) do
    {opts, _, _} = OptionParser.parse(args, strict: @switches)

    # what the dashboard shows in its title: how this server was started
    Application.put_env(:actor_tx_spike, :server_info, %{
      args: args,
      settings: Map.put_new(Map.new(opts), :port, 4040)
    })

    Mix.Task.run("app.start")

    # logs left by a previous run: load them before this run starts appending
    previous = previous_days(opts)
    lab_dir = start_logs(opts)

    start_world(opts, previous, lab_dir)
    panel = start_panel(opts)

    Mix.shell().info("""

    ActorTxSpike live demo
      dashboard: http://127.0.0.1:#{ActorTxSpike.Panel.port(panel)}
      stop:      Ctrl-C twice
    """)

    unless opts[:quiet], do: spawn_link(&narrate/0)
    Process.sleep(:infinity)
  end

  defp start_world(opts, previous, lab_dir) do
    {:ok, _} = Demo.start_link(sentinel_options(opts[:sentinel_ms]))
    {:ok, _} = Demo.Bulk.start_link()
    {:ok, _} = ActorTxSpike.Review.start_link(providers: &Demo.review_providers/0)
    recover(previous)
    ActorTxSpike.Review.load(for day <- previous, record <- day.records, do: record)
    start_lab(lab_dir)
    set_pace(opts[:pace])
    limit_in_flight(opts[:max_in_flight])
  end

  defp limit_in_flight(nil), do: :ok
  defp limit_in_flight(n), do: Demo.control(%{"action" => "admission", "max_in_flight" => n})

  defp sentinel_options(nil), do: []
  defp sentinel_options(ms), do: [sentinel_interval_ms: ms]

  defp set_pace(nil), do: :ok
  defp set_pace(factor), do: Demo.control(%{"action" => "pace", "factor" => factor})

  defp start_panel(opts) do
    {:ok, panel} =
      Panel.start_link(
        handler: Demo.Handler,
        port: opts[:port] || 4040,
        perf_history: opts[:perf_history]
      )

    if opts[:auto], do: Demo.control(%{"action" => "auto", "on" => true})
    panel
  end

  defp start_audit(path) do
    case ActorTxSpike.Audit.start_link(path: path) do
      {:ok, _} -> Mix.shell().info("audit log: #{path} (verify with: mix audit.verify #{path})")
      {:error, reason} -> Mix.raise("audit log refused to start: #{inspect(reason)}")
    end
  end

  defp previous_days(opts) do
    (List.wrap(opts[:audit]) ++ logs_in(opts[:audit_dir])) |> Enum.flat_map(&previous_day/1)
  end

  defp logs_in(nil), do: []
  defp logs_in(dir), do: Path.wildcard(Path.join(dir, "*.log"))

  defp previous_day(path) do
    with true <- File.exists?(path), {:ok, day} <- Replay.load(path), do: [day], else: (_ -> [])
  end

  defp recover([]), do: :ok

  defp recover(days) do
    results = Demo.recover(days)

    for {tx, result} <- results,
        do:
          Mix.shell().info(
            "recovery: #{tx} #{if result == :ok, do: "resumed", else: inspect(result)}"
          )
  end

  defp start_logs(opts) do
    if opts[:audit], do: start_audit(opts[:audit])
    if opts[:audit_dir], do: start_regional_audit(opts[:audit_dir])
    if opts[:lab], do: start_lab_logs()
  end

  # The lab's logs are supervised (so killing the router can be tested) and live in a
  # temporary directory that is fresh every run.
  defp start_lab_logs do
    dir = Path.join(System.tmp_dir!(), "actor-tx-lab-#{System.system_time(:second)}")

    {:ok, _} =
      Supervisor.start_link([{Router, dir: dir, logs: Demo.log_names()}], strategy: :one_for_one)

    dir
  end

  defp start_lab(nil), do: :ok

  defp start_lab(dir) do
    {:ok, _} = Demo.Lab.start_link(dir: dir, supervised: true)
    Mix.shell().info("test lab: regional logs in #{dir}; open the Test lab tab")
  end

  defp start_regional_audit(dir) do
    case Router.start_link(dir: dir, logs: Demo.log_names()) do
      {:ok, router} ->
        logs = router |> Router.paths() |> Map.keys() |> Enum.sort()
        Mix.shell().info("regional audit logs in #{dir}: #{Enum.join(logs, ", ")}")

      {:error, reason} ->
        Mix.raise("regional audit logs refused to start: #{inspect(reason)}")
    end
  end

  defp narrate do
    ActorTxSpike.Events.subscribe() |> Enum.each(fn _ -> :ok end)
    loop()
  end

  defp loop do
    receive do
      {:tx_event, event} ->
        unless ActorTxSpike.Journal.journal?(event.actor),
          do: IO.puts(ActorTxSpike.Narrative.format(event, color: true))

        loop()
    end
  end
end

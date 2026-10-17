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
  """
  use Mix.Task

  alias ActorTxSpike.Audit.Router
  alias ActorTxSpike.Demo
  alias ActorTxSpike.Replay

  @impl true
  def run(args) do
    {opts, _, _} =
      OptionParser.parse(args,
        strict: [
          port: :integer,
          auto: :boolean,
          quiet: :boolean,
          pace: :float,
          sentinel_ms: :integer,
          audit: :string,
          audit_dir: :string
        ]
      )

    Mix.Task.run("app.start")

    # logs left by a previous run: load them before this run starts appending
    previous = previous_days(opts)
    if opts[:audit], do: start_audit(opts[:audit])
    if opts[:audit_dir], do: start_regional_audit(opts[:audit_dir])

    demo_opts = if opts[:sentinel_ms], do: [sentinel_interval_ms: opts[:sentinel_ms]], else: []
    {:ok, _} = ActorTxSpike.Demo.start_link(demo_opts)
    recover(previous)
    if opts[:pace], do: ActorTxSpike.Demo.control(%{"action" => "pace", "factor" => opts[:pace]})

    {:ok, panel} =
      ActorTxSpike.Panel.start_link(handler: ActorTxSpike.Demo.Handler, port: opts[:port] || 4040)

    if opts[:auto], do: ActorTxSpike.Demo.control(%{"action" => "auto", "on" => true})

    Mix.shell().info("""

    ActorTxSpike live demo
      dashboard: http://127.0.0.1:#{ActorTxSpike.Panel.port(panel)}
      stop:      Ctrl-C twice
    """)

    unless opts[:quiet], do: spawn_link(&narrate/0)
    Process.sleep(:infinity)
  end

  defp start_audit(path) do
    case ActorTxSpike.Audit.start_link(path: path) do
      {:ok, _} -> Mix.shell().info("audit log: #{path} (verify with: mix audit.verify #{path})")
      {:error, reason} -> Mix.raise("audit log refused to start: #{inspect(reason)}")
    end
  end

  defp previous_days(opts) do
    paths =
      List.wrap(opts[:audit]) ++
        if(opts[:audit_dir], do: Path.wildcard(Path.join(opts[:audit_dir], "*.log")), else: [])

    Enum.flat_map(paths, fn path ->
      case File.exists?(path) && Replay.load(path) do
        {:ok, day} -> [day]
        _ -> []
      end
    end)
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

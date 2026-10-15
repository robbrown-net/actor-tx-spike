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
  """
  use Mix.Task

  @impl true
  def run(args) do
    {opts, _, _} =
      OptionParser.parse(args,
        strict: [
          port: :integer,
          auto: :boolean,
          quiet: :boolean,
          pace: :float,
          sentinel_ms: :integer
        ]
      )

    Mix.Task.run("app.start")

    demo_opts = if opts[:sentinel_ms], do: [sentinel_interval_ms: opts[:sentinel_ms]], else: []
    {:ok, _} = ActorTxSpike.Demo.start_link(demo_opts)
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

  defp narrate do
    ActorTxSpike.Events.subscribe() |> Enum.each(fn _ -> :ok end)
    loop()
  end

  defp loop do
    receive do
      {:tx_event, event} ->
        IO.puts(ActorTxSpike.Narrative.format(event, color: true))
        loop()
    end
  end
end

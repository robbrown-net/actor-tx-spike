defmodule Mix.Tasks.Demo do
  @shortdoc "Run the live demo world and dashboard"
  @moduledoc """
  Starts the system with five fake FX providers, serves the dashboard and
  streams the narrative to the terminal.

      mix demo                 # dashboard on http://127.0.0.1:4040
      mix demo --port 4545
      mix demo --auto          # start with automatic traffic
      mix demo --quiet         # do not print the narrative to the terminal
  """
  use Mix.Task

  @impl true
  def run(args) do
    {opts, _, _} =
      OptionParser.parse(args, strict: [port: :integer, auto: :boolean, quiet: :boolean])

    Mix.Task.run("app.start")

    {:ok, _} = ActorTxSpike.Demo.start_link()

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

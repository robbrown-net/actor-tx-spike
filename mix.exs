defmodule ActorTxSpike.MixProject do
  use Mix.Project

  def project do
    [
      app: :actor_tx_spike,
      version: "0.1.0",
      elixir: "~> 1.15",
      start_permanent: Mix.env() == :prod,
      deps: deps(),
      aliases: aliases(),
      test_coverage: [
        summary: [threshold: coverage_threshold()],
        ignore_modules: ignored_modules()
      ],
      elixirc_paths: elixirc_paths(Mix.env())
    ]
  end

  def application do
    [
      extra_applications: [:logger, :crypto],
      mod: {ActorTxSpike.Application, []}
    ]
  end

  defp elixirc_paths(:test), do: ["lib", "test/support"]
  defp elixirc_paths(_), do: ["lib"]

  # Gates: unit 100%, integration 85% (see docs/ROADMAP.md).
  defp aliases do
    [
      "test.unit": [
        "cmd env MIX_ENV=test COVERAGE_THRESHOLD=100 COVERAGE_IGNORE_TOOLING=1 mix test --cover test/unit"
      ],
      "test.integration": [
        "cmd env MIX_ENV=test COVERAGE_THRESHOLD=85 mix test --cover test/integration"
      ]
    ]
  end

  defp coverage_threshold do
    "COVERAGE_THRESHOLD" |> System.get_env("0") |> String.to_integer()
  end

  # Tooling modules (demo world, dashboard server) are exercised by integration
  # tests but excluded from the strict unit gate. Keep this list short.
  @tooling [ActorTxSpike.Demo, ActorTxSpike.Demo.Handler]

  defp ignored_modules do
    # Mix.Tasks.Demo is a thin entry point and is never measured.
    always = [Mix.Tasks.Demo]
    if System.get_env("COVERAGE_IGNORE_TOOLING") == "1", do: always ++ @tooling, else: always
  end

  defp deps do
    []
  end
end

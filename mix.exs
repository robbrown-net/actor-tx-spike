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
      ],
      "test.regression": ["cmd env MIX_ENV=test mix test --only regression"],
      "test.system": ["cmd env MIX_ENV=test mix test --include system test/system"],
      # the pre-commit check: format, static analysis incl. the code-shape limits, unit gate
      quality: ["format --check-formatted", "credo --strict", "test.unit"]
    ]
  end

  defp coverage_threshold do
    "COVERAGE_THRESHOLD" |> System.get_env("0") |> String.to_integer()
  end

  # Tooling modules (demo world, dashboard server) are exercised by integration
  # tests but excluded from the strict unit gate. Keep this list short.
  @tooling [
    ActorTxSpike.Demo,
    ActorTxSpike.Demo.Handler,
    ActorTxSpike.Demo.Lab,
    ActorTxSpike.Demo.Lab.Scenarios
  ]

  defp ignored_modules do
    # Mix.Tasks.Demo, Mix.Tasks.Audit.Verify and Mix.Tasks.Replay are thin entry points, and
    # ActorTxSpike.Wait (test/support) is a test helper: none of them is measured.
    always = [Mix.Tasks.Demo, Mix.Tasks.Audit.Verify, Mix.Tasks.Replay, ActorTxSpike.Wait]
    if System.get_env("COVERAGE_IGNORE_TOOLING") == "1", do: always ++ @tooling, else: always
  end

  # Analysis tooling only: never part of the runtime release.
  defp deps do
    [
      {:credo, "~> 1.7", only: [:dev, :test], runtime: false},
      {:sobelow, "~> 0.13", only: [:dev, :test], runtime: false},
      {:mix_audit, "~> 2.1", only: [:dev, :test], runtime: false}
    ]
  end
end

defmodule ActorTxSpike.Costs do
  @moduledoc """
  A simple, explicit cost model: a rate card that turns raw usage into money.

  Costs are integers in **pico-units** (10^-12 of a major currency unit), because
  one transaction costs a tiny fraction of a cent and coarser units would
  round it to zero. Value (savings, margin) is in minor units; use
  `picos_per_minor/0` to put the two side by side.

  The default card holds *illustrative* cloud-ish prices, clearly placeholders:
  replace them with the real invoice figures. Every price is a field, so a
  scenario ("what if egress doubles?") is a one-line change.

  Assumption: costs and values are reported in the same currency. Converting
  between them is a reporting concern for later.
  """

  alias ActorTxSpike.Money

  @picos_per_minor 10_000_000_000

  defstruct cpu_picos_per_million_reductions: 111_000,
            memory_picos_per_gib_second: 1_389_000,
            network_picos_per_gib_out: 90_000_000_000,
            network_picos_per_gib_in: 0,
            state_change_picos: 250_000,
            provider_call_picos: 50_000_000,
            platform_picos_per_hour: 83_200_000_000

  @type t :: %__MODULE__{}

  @type usage :: %{
          reductions: non_neg_integer(),
          memory_bytes: non_neg_integer(),
          duration_ms: non_neg_integer(),
          provider_calls: non_neg_integer(),
          bytes_out: non_neg_integer(),
          bytes_in: non_neg_integer(),
          state_changes: non_neg_integer(),
          compensations: non_neg_integer()
        }

  @gib 1_073_741_824

  @doc "Pico-units in one minor unit (a cent is 10,000,000,000 pico-units)."
  @spec picos_per_minor() :: pos_integer()
  def picos_per_minor, do: @picos_per_minor

  @doc "A rate card, with any price overridden."
  @spec new(keyword()) :: t()
  def new(overrides \\ []), do: struct!(__MODULE__, overrides)

  @doc "Price one transaction's measured usage. Every figure is in pico-units."
  @spec price(t(), usage()) :: %{
          cpu: integer(),
          memory: integer(),
          network: integer(),
          storage: integer(),
          provider_calls: integer(),
          total: integer()
        }
  def price(%__MODULE__{} = card, usage) do
    parts = %{
      cpu: scale(usage.reductions * card.cpu_picos_per_million_reductions, 1_000_000),
      memory:
        scale(
          usage.memory_bytes * usage.duration_ms * card.memory_picos_per_gib_second,
          @gib * 1_000
        ),
      network:
        scale(usage.bytes_out * card.network_picos_per_gib_out, @gib) +
          scale(usage.bytes_in * card.network_picos_per_gib_in, @gib),
      storage: usage.state_changes * card.state_change_picos,
      provider_calls: usage.provider_calls * card.provider_call_picos
    }

    Map.put(parts, :total, parts |> Map.values() |> Enum.sum())
  end

  @doc "The always-on platform cost of running for `elapsed_ms`."
  @spec platform(t(), non_neg_integer()) :: integer()
  def platform(%__MODULE__{platform_picos_per_hour: hourly}, elapsed_ms),
    do: scale(elapsed_ms * hourly, 3_600_000)

  defp scale(numerator, denominator), do: Money.div_half_even(numerator, denominator)
end

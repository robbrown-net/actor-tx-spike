defmodule ActorTxSpike.Guarantee do
  @moduledoc """
  A standing rate guarantee a merchant holds for a period (e.g. a weekly
  guaranteed rate). Per transaction we may route better or worse than the
  guarantee; the difference is *our* margin (positive) or exposure (negative).

      net guaranteed = amount * guaranteed_rate
      margin         = achieved_net - net guaranteed
  """

  @enforce_keys [:merchant_id, :period, :rate]
  defstruct [:merchant_id, :period, :rate]

  @type t :: %__MODULE__{}

  @spec new(map()) :: {:ok, t()} | {:error, [String.t()]}
  def new(data) when is_map(data) do
    data = Map.new(data, fn {k, v} -> {to_string(k), v} end)
    merchant = Map.get(data, "merchant_id")
    period = Map.get(data, "period")
    rate = Map.get(data, "rate")

    errors =
      [
        {is_binary(merchant) and merchant != "", "merchant_id: required"},
        {is_binary(period) and period != "", "period: required (e.g. \"2026-W42\")"},
        {is_number(rate) and rate > 0, "rate: must be a positive number"}
      ]
      |> Enum.reject(&elem(&1, 0))
      |> Enum.map(&elem(&1, 1))

    if errors == [],
      do: {:ok, %__MODULE__{merchant_id: merchant, period: period, rate: rate}},
      else: {:error, errors}
  end

  @doc "Compare what we achieved with what we guaranteed."
  @spec evaluate(t(), number(), number()) :: %{
          guaranteed_net: float(),
          achieved_net: float(),
          margin: float()
        }
  def evaluate(%__MODULE__{rate: rate}, amount, achieved_net) do
    guaranteed = amount * rate * 1.0

    %{
      guaranteed_net: guaranteed,
      achieved_net: achieved_net * 1.0,
      margin: achieved_net - guaranteed
    }
  end
end

defmodule ActorTxSpike.Guarantee do
  @moduledoc """
  A standing rate guarantee a merchant holds for a period (e.g. a weekly
  guaranteed rate). Per transaction we may route better or worse than the
  guarantee; the difference is *our* margin (positive) or exposure (negative).

      net guaranteed = amount * guaranteed_rate   (integer minor units, half-even)
      margin         = achieved_net - net guaranteed
  """

  alias ActorTxSpike.Money

  @enforce_keys [:merchant_id, :period, :rate]
  defstruct [:merchant_id, :period, :rate]

  @type t :: %__MODULE__{}

  @spec new(map()) :: {:ok, t()} | {:error, [String.t()]}
  def new(data) when is_map(data) do
    data = Map.new(data, fn {k, v} -> {to_string(k), v} end)
    merchant = Map.get(data, "merchant_id")
    period = Map.get(data, "period")
    rate = data |> Map.get("rate") |> to_rate()

    errors =
      [
        {is_binary(merchant) and merchant != "", "merchant_id: required"},
        {is_binary(period) and period != "", "period: required (e.g. \"2026-W42\")"},
        {is_integer(rate) and rate > 0, "rate: must be a positive number"}
      ]
      |> Enum.reject(&elem(&1, 0))
      |> Enum.map(&elem(&1, 1))

    if errors == [],
      do: {:ok, %__MODULE__{merchant_id: merchant, period: period, rate: rate}},
      else: {:error, errors}
  end

  @doc "Compare what we achieved with what we guaranteed."
  @spec evaluate(t(), Money.minor(), Money.minor()) :: %{
          guaranteed_net: Money.minor(),
          achieved_net: Money.minor(),
          margin: Money.minor()
        }
  def evaluate(%__MODULE__{rate: rate}, amount, achieved_net) do
    guaranteed = Money.convert(amount, rate)
    %{guaranteed_net: guaranteed, achieved_net: achieved_net, margin: achieved_net - guaranteed}
  end

  defp to_rate(v) when is_number(v) or is_binary(v) do
    Money.rate(v)
  rescue
    _ -> nil
  end

  defp to_rate(_), do: nil
end

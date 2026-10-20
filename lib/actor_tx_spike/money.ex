defmodule ActorTxSpike.Money do
  @moduledoc """
  Exact money arithmetic. No floats ever hold money.

    * **amounts and fees** are integers in *minor units* (e.g. cents)
    * **rates** are integers scaled by `10^8` (`1.0820` is `108_200_000`)

  Decimal input (a float literal, a string, an integer) is converted exactly
  at the edge via its shortest decimal representation, so `1.0877` becomes
  `108_770_000` and not a binary approximation. Rounding is half-even
  (banker's), the convention for financial conversions.
  """

  @rate_scale 100_000_000

  @type minor :: integer()
  @type rate :: pos_integer()

  @doc "Scale factor of a rate (10^8)."
  @spec rate_scale() :: pos_integer()
  def rate_scale, do: @rate_scale

  @doc "Convert a decimal number or string to a scaled-integer rate."
  @spec rate(number() | String.t()) :: rate()
  def rate(value), do: scaled(value, @rate_scale)

  @doc "Convert a major-unit decimal (e.g. 12.34) to integer minor units (1234)."
  @spec minor(number() | String.t()) :: minor()
  def minor(value), do: scaled(value, 100)

  @doc "`amount_minor * rate`, rounded half-even to a whole minor unit."
  @spec convert(minor(), rate()) :: minor()
  def convert(amount_minor, rate), do: div_half_even(amount_minor * rate, @rate_scale)

  @doc "A rate for display, e.g. `1.082`. Display only; never compute with it."
  @spec rate_to_float(rate()) :: float()
  def rate_to_float(rate), do: rate / @rate_scale

  @doc "Minor units for display, e.g. `1085.0` for 108_500. Display only."
  @spec to_major(minor()) :: float()
  def to_major(minor), do: minor / 100

  @doc "Format minor units as a decimal string with two places."
  @spec format(minor()) :: String.t()
  def format(minor) do
    sign = if minor < 0, do: "-", else: ""
    abs = Kernel.abs(minor)

    "#{sign}#{div(abs, 100)}.#{abs |> rem(100) |> Integer.to_string() |> String.pad_leading(2, "0")}"
  end

  @doc "Format a rate with up to 8 decimals, trailing zeros trimmed to at least 4."
  @spec format_rate(rate()) :: String.t()
  def format_rate(rate) do
    frac = rate |> rem(@rate_scale) |> Integer.to_string() |> String.pad_leading(8, "0")
    trimmed = frac |> String.trim_trailing("0") |> String.pad_trailing(4, "0")
    "#{div(rate, @rate_scale)}.#{trimmed}"
  end

  @doc "Integer division rounding half to even."
  @spec div_half_even(integer(), pos_integer()) :: integer()
  def div_half_even(n, d) when d > 0 do
    q = Integer.floor_div(n, d)
    r = n - q * d
    twice = 2 * r

    round_half_even(q, twice, d)
  end

  defp round_half_even(q, twice, d) when twice < d, do: q
  defp round_half_even(q, twice, d) when twice > d, do: q + 1
  defp round_half_even(q, _twice, _d) when rem(q, 2) == 0, do: q
  defp round_half_even(q, _twice, _d), do: q + 1

  defp scaled(value, factor) when is_integer(value), do: value * factor

  defp scaled(value, factor) when is_float(value),
    do: value |> :erlang.float_to_binary([:short]) |> scaled(factor)

  defp scaled(value, factor) when is_binary(value) do
    {sign, digits} =
      case value do
        "-" <> rest -> {-1, rest}
        rest -> {1, rest}
      end

    {mantissa, exp} = split_exponent(digits)
    {whole, frac} = split_point(mantissa)
    numerator = String.to_integer(whole <> frac)
    shift = exp - String.length(frac)

    # value = numerator * 10^shift; result = value * factor, rounded half-even
    scaled =
      if shift >= 0,
        do: numerator * pow10(shift) * factor,
        else: div_half_even(numerator * factor, pow10(-shift))

    sign * scaled
  end

  defp split_exponent(s) do
    case String.split(String.downcase(s), "e") do
      [m] -> {m, 0}
      [m, e] -> {m, String.to_integer(e)}
    end
  end

  defp split_point(s) do
    case String.split(s, ".") do
      [w] -> {w, ""}
      [w, f] -> {if(w == "", do: "0", else: w), f}
    end
  end

  defp pow10(n), do: Integer.pow(10, n)
end

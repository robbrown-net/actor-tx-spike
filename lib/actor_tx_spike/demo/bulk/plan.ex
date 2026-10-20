defmodule ActorTxSpike.Demo.Bulk.Plan do
  @moduledoc """
  A validated plan for sending many transactions, and the pure generator that turns it
  into one `submit` command at a time.

  Every transaction gets an amount drawn uniformly between `min` and `max` (a single
  value when they are equal). In `:fixed` mode every other setting comes from `fixed`
  (what the header of the dashboard shows). In `:random` mode each setting listed in
  `randomise` is drawn at random for every transaction and the rest come from `fixed`.

  The generator takes and returns a random state, so a plan with a `seed` produces the
  same sequence every time: a random batch can be repeated exactly.

  Parameters (string keys, as decoded from JSON): `count` (1 to 10,000), `min_amount`,
  `max_amount` (major units, decimal), `interval_ms` (0..60_000, the pause between
  submissions), `mode` (`"fixed"` or `"random"`), `randomise` (list of setting names),
  `fixed` (map of setting to value), `seed` (integer, optional).
  """

  alias ActorTxSpike.{Money, Validation}

  @max_count 10_000
  @max_interval_ms 60_000
  @currencies ~w(USD EUR GBP INR CNY MXN BRL TRY SAR JPY KRW)
  @regions [nil, "UK", "EU", "IN", "CN"]

  @choices %{
    "merchant" => [nil, "acme", "globex"],
    "customer" => [nil, "vip-1"],
    "origin" => @regions,
    "region" => @regions,
    "instrument" => ["spot", "forward"],
    "customer_type" => ["business", "retail"],
    "source_currency" => @currencies,
    "destination_currency" => @currencies
  }
  @settings Map.keys(@choices) |> Enum.sort()
  # the source currency is drawn before the destination, which must differ from it
  @draw_order ~w(merchant customer origin region instrument customer_type source_currency destination_currency)

  defstruct count: 10,
            min: 1_000,
            max: 100_000,
            interval_ms: 100,
            mode: :fixed,
            randomise: @settings,
            fixed: %{},
            seed: nil

  @type t :: %__MODULE__{}

  @doc "The settings a plan can randomise or fix."
  @spec settings() :: [String.t()]
  def settings, do: @settings

  @doc "The values each setting is drawn from (`nil` means 'not given')."
  @spec choices() :: %{String.t() => [String.t() | nil]}
  def choices, do: @choices

  @doc "Validate parameters into a plan, reporting every problem."
  @spec new(map()) :: {:ok, t()} | {:error, [String.t()]}
  def new(params) do
    {values, errors} =
      params |> Validation.stringify() |> Validation.parse_fields(fields(), "", [])

    plan = struct!(__MODULE__, values)

    case errors do
      [] ->
        Validation.build(plan, [
          {plan.max >= plan.min, "max_amount: must not be below min_amount"}
        ])

      _ ->
        {:error, errors}
    end
  end

  @doc "The starting random state for a plan (seeded plans repeat exactly)."
  @spec start(t()) :: :rand.state()
  def start(%__MODULE__{seed: nil}), do: :rand.seed_s(:exsss)
  def start(%__MODULE__{seed: seed}), do: :rand.seed_s(:exsss, seed)

  @doc "The next `submit` control command, and the new random state."
  @spec next(t(), :rand.state()) :: {map(), :rand.state()}
  def next(%__MODULE__{} = plan, rand) do
    {offset, rand} = :rand.uniform_s(plan.max - plan.min + 1, rand)
    {settings, rand} = Enum.reduce(@draw_order, {%{}, rand}, &setting(plan, &1, &2))
    amount = Money.to_major(plan.min + offset - 1)
    {Map.merge(settings, %{"action" => "submit", "amount" => amount}), rand}
  end

  @doc "A one-line description for the dashboard."
  @spec describe(t()) :: String.t()
  def describe(%__MODULE__{} = plan) do
    "#{plan.count} × #{Money.format(plan.min)} to #{Money.format(plan.max)}, " <>
      "#{plan.interval_ms} ms apart, #{plan.mode}" <> seeded(plan.seed)
  end

  defp seeded(nil), do: ""
  defp seeded(seed), do: ", seed #{seed}"

  # ---- drawing a transaction's settings ------------------------------------

  defp setting(plan, key, {acc, rand}) do
    {value, rand} = value(plan, key, acc, rand)
    {put_given(acc, key, value), rand}
  end

  defp value(%{mode: :random, randomise: chosen} = plan, key, acc, rand) do
    if key in chosen, do: draw(key, acc, rand), else: {plan.fixed[key], rand}
  end

  defp value(plan, key, _acc, rand), do: {plan.fixed[key], rand}

  defp draw(key, acc, rand), do: key |> options(acc) |> pick(rand)

  # A payment converts one currency into a different one.
  defp options("destination_currency", acc), do: @currencies -- [acc["source_currency"]]
  defp options(key, _acc), do: @choices[key]

  defp pick(options, rand) do
    {n, rand} = :rand.uniform_s(length(options), rand)
    {Enum.at(options, n - 1), rand}
  end

  defp put_given(map, _key, nil), do: map
  defp put_given(map, key, value), do: Map.put(map, key, value)

  # ---- parsing -------------------------------------------------------------

  defp fields do
    [
      {:count, "count", &count/1},
      {:min, "min_amount", &amount/1},
      {:max, "max_amount", &amount/1},
      {:interval_ms, "interval_ms", &interval/1},
      {:mode, "mode", &mode/1},
      {:randomise, "randomise", &randomise/1},
      {:fixed, "fixed", &fixed/1},
      {:seed, "seed", &seed/1}
    ]
  end

  defp count(n) when is_integer(n) and n in 1..@max_count, do: {:ok, n}
  defp count(_), do: {:error, "must be a whole number from 1 to #{@max_count}"}

  defp interval(n) when is_integer(n) and n in 0..@max_interval_ms, do: {:ok, n}

  defp interval(_),
    do: {:error, "must be a whole number of milliseconds from 0 to #{@max_interval_ms}"}

  defp amount(value) when is_number(value) or is_binary(value),
    do: value |> minor() |> positive()

  defp amount(_), do: positive(nil)

  defp positive(n) when is_integer(n) and n > 0, do: {:ok, n}
  defp positive(_), do: {:error, "must be a positive amount"}

  defp minor(value) do
    Money.minor(value)
  rescue
    _ -> nil
  end

  defp mode("fixed"), do: {:ok, :fixed}
  defp mode("random"), do: {:ok, :random}
  defp mode(_), do: {:error, ~s(must be "fixed" or "random")}

  defp randomise(list) when is_list(list) do
    case Enum.reject(list, &(&1 in @settings)) do
      [] -> {:ok, list}
      unknown -> {:error, "unknown setting(s): #{Enum.join(unknown, ", ")}"}
    end
  end

  defp randomise(_), do: {:error, "must be a list of setting names"}

  defp fixed(map) when is_map(map) do
    map = Validation.stringify(map)

    case Map.keys(map) -- @settings do
      [] -> {:ok, Map.reject(map, fn {_k, v} -> v in [nil, ""] end)}
      unknown -> {:error, "unknown setting(s): #{Enum.join(unknown, ", ")}"}
    end
  end

  defp fixed(_), do: {:error, "must be a map of setting to value"}

  defp seed(nil), do: {:ok, nil}
  defp seed(n) when is_integer(n), do: {:ok, n}
  defp seed(_), do: {:error, "must be a whole number or empty"}
end

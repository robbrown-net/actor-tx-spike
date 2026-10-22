defmodule ActorTxSpike.Eligibility.Parser do
  @moduledoc """
  Turns plain data (string or atom keys) into validated `Eligibility.Rule`s,
  collecting every error found rather than stopping at the first.
  """

  alias ActorTxSpike.Eligibility.Rule
  alias ActorTxSpike.Validation

  @top_keys ~w(default regions)
  @rule_keys ~w(instruments customer_types currencies providers logs local_only retention_days)
  @instruments [:spot, :forward]
  @customer_types [:business, :retail]
  @providers_help ~s(must be "all", {"allow": [...]} or {"deny": [...]})

  @doc "`{:ok, {default_rule, %{region => rule}}}` or every error found."
  @spec parse(map()) :: {:ok, {Rule.t(), %{String.t() => Rule.t()}}} | {:error, [String.t()]}
  def parse(data) do
    data = Validation.stringify(data)
    unknown = for key <- Map.keys(data) -- @top_keys, do: "unknown key: #{key}"
    {default, default_errors} = rule(Map.get(data, "default", %{}), "default")
    {regions, region_errors} = regions(Map.get(data, "regions", %{}))

    case unknown ++ default_errors ++ region_errors do
      [] -> {:ok, {default, regions}}
      errors -> {:error, errors}
    end
  end

  defp regions(raw_regions) do
    Enum.reduce(raw_regions, {%{}, []}, fn {code, raw}, {acc, errs} ->
      {rule, e} = rule(raw, "regions.#{code}")
      {Map.put(acc, to_string(code), rule), errs ++ e}
    end)
  end

  defp rule(raw, path) when is_map(raw) do
    raw = Validation.stringify(raw)
    unknown = for key <- Map.keys(raw) -- @rule_keys, do: "#{path}: unknown key #{key}"

    {values, errors} = Validation.parse_fields(raw, fields(), path <> ".", unknown)

    {struct!(Rule, values), errors}
  end

  defp rule(_raw, path), do: {%Rule{}, ["#{path}: must be a map"]}

  defp fields do
    [
      {:instruments, "instruments", &enum_list(&1, @instruments)},
      {:customer_types, "customer_types", &enum_list(&1, @customer_types)},
      {:currencies, "currencies", &currencies/1},
      {:providers, "providers", &providers/1},
      {:logs, "logs", &logs/1},
      {:local_only, "local_only", &boolean/1},
      {:retention_days, "retention_days", &retention/1}
    ]
  end

  defp enum_list([_ | _] = list, allowed) do
    parsed = Enum.map(list, fn v -> Enum.find(allowed, &(Atom.to_string(&1) == to_string(v))) end)

    if Enum.any?(parsed, &is_nil/1),
      do: {:error, "unknown value (expected some of #{inspect(allowed)})"},
      else: {:ok, parsed}
  end

  defp enum_list(_, _), do: {:error, "must be a non-empty list"}

  defp currencies("all"), do: {:ok, :all}

  defp currencies([_ | _] = list) do
    if Enum.all?(list, &(is_binary(&1) and &1 =~ ~r/^[A-Z]{3}$/)),
      do: {:ok, list},
      else: {:error, "currency codes are three capital letters"}
  end

  defp currencies(_), do: {:error, ~s(must be "all" or a non-empty list of currency codes)}

  defp providers("all"), do: {:ok, :all}
  defp providers(%{} = map), do: map |> Validation.stringify() |> provider_list()
  defp providers(_), do: {:error, @providers_help}

  defp provider_list(%{"allow" => list} = map) when is_list(list) and map_size(map) == 1,
    do: {:ok, {:allow, Enum.map(list, &to_string/1)}}

  defp provider_list(%{"deny" => list} = map) when is_list(list) and map_size(map) == 1,
    do: {:ok, {:deny, Enum.map(list, &to_string/1)}}

  defp provider_list(_), do: {:error, @providers_help}

  defp logs([_ | _] = logs) do
    if Enum.all?(logs, &(is_binary(&1) and &1 =~ ~r/^[A-Za-z0-9_-]+$/)),
      do: {:ok, logs},
      else: {:error, "log names must be letters, digits, - or _"}
  end

  defp logs(_), do: {:error, "must be a non-empty list of log names"}

  defp boolean(v) when is_boolean(v), do: {:ok, v}
  defp boolean(_), do: {:error, "must be true or false"}

  defp retention(nil), do: {:ok, nil}
  defp retention(v) when is_integer(v) and v > 0, do: {:ok, v}
  defp retention(_), do: {:error, "must be nil or a positive number of days"}
end

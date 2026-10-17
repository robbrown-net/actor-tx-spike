defmodule ActorTxSpike.Eligibility do
  @moduledoc """
  Jurisdiction as a hard constraint.

  Regulation decides which providers may carry a corridor, which instruments and
  customer types are allowed there, and where the day's records may live. These
  are not preferences to be weighed by a score: they are applied *before*
  scoring, a provider that is not eligible is never even asked (it should not
  receive the request at all), and the decision is recorded in the journal so a
  replay can prove it was respected.

  Rules are declarative data, validated when built:

      Eligibility.new(%{
        "default" => %{"instruments" => ["spot", "forward"], "customer_types" => ["business", "retail"]},
        "regions" => %{
          "IN" => %{
            "instruments" => ["spot"],
            "providers" => %{"allow" => ["northwind", "cobalt"]},
            "logs" => ["IN"],
            "local_only" => true,
            "retention_days" => 3650
          }
        }
      })

  A transaction touches the regions named by its `:origin_region` and
  `:destination_region`; a region without a rule uses the default. Where several
  regions apply the strictest result wins: an instrument or customer type must be
  allowed by *all*, a provider by *all*, retention is the *longest*, and records go
  to the union of the regions' logs unless a `local_only` region is involved, in
  which case they go only to the local-only regions' logs.

  Rule fields: `instruments`, `customer_types`, `providers` (`"all"`, or
  `{"allow": [...]}`, or `{"deny": [...]}`), `logs` (default `["GLOBAL"]`),
  `local_only` (default false), `retention_days` (default none).
  """

  @rule_keys ~w(instruments customer_types providers logs local_only retention_days)
  @instruments [:spot, :forward]
  @customer_types [:business, :retail]

  alias ActorTxSpike.Eligibility.Rule

  defstruct default: %Rule{}, regions: %{}
  @type t :: %__MODULE__{default: Rule.t(), regions: %{String.t() => Rule.t()}}

  @doc "Build from plain data (string or atom keys). Returns every error found."
  @spec new(map()) :: {:ok, t()} | {:error, [String.t()]}
  def new(data) when is_map(data) do
    data = stringify(data)

    unknown =
      for key <- Map.keys(data) -- ~w(default regions), do: "unknown key: #{key}"

    {default, e1} = parse_rule(Map.get(data, "default", %{}), "default")

    {regions, e2} =
      data
      |> Map.get("regions", %{})
      |> Enum.reduce({%{}, []}, fn {code, raw}, {acc, errs} ->
        {rule, e} = parse_rule(raw, "regions.#{code}")
        {Map.put(acc, to_string(code), rule), errs ++ e}
      end)

    case unknown ++ e1 ++ e2 do
      [] -> {:ok, %__MODULE__{default: default, regions: regions}}
      errors -> {:error, errors}
    end
  end

  @doc "Everything permitted, everything in the global log: for when no rules are configured."
  @spec open() :: t()
  def open, do: %__MODULE__{}

  @doc """
  Decide a request. Returns a plain map, suitable for the journal:
  `permitted?`, `reasons` (why not), `regions`, `instrument`, `customer_type`,
  `rules` (what applied), `logs`, `retention_days`.

  The decision also answers `provider_allowed?/2`.
  """
  @spec decide(t(), map()) :: map()
  def decide(%__MODULE__{} = e, request) do
    regions =
      for key <- [:origin_region, :destination_region],
          r = request[key],
          uniq: true,
          do: to_string(r)

    rules = if regions == [], do: [e.default], else: Enum.map(regions, &rule_for(e, &1))
    instrument = to_atom(request[:instrument], :spot)
    customer = to_atom(request[:customer_type], :business)

    reasons =
      for {region, rule} <- pairs(regions, rules),
          reason <- [
            instrument not in rule.instruments && "#{instrument} is not permitted in #{region}",
            customer not in rule.customer_types &&
              "#{customer} customers are not permitted in #{region}"
          ],
          reason,
          do: reason

    %{
      permitted?: reasons == [],
      reasons: reasons,
      regions: regions,
      instrument: instrument,
      customer_type: customer,
      provider_rules: for(r <- rules, r.providers != :all, do: provider_rule(r.providers)),
      logs: logs(rules),
      retention_days:
        rules
        |> Enum.map(& &1.retention_days)
        |> Enum.reject(&is_nil/1)
        |> Enum.max(fn -> nil end)
    }
  end

  @doc "May this provider carry the transaction decided by `decision`?"
  @spec provider_allowed?(map(), term()) :: boolean()
  def provider_allowed?(%{provider_rules: rules}, provider_id) do
    id = to_string(provider_id)

    Enum.all?(rules, fn
      %{allow: list} -> id in list
      %{deny: list} -> id not in list
    end)
  end

  @doc "Why a provider is not allowed, for the record."
  @spec denial_reason(map(), term()) :: String.t()
  def denial_reason(%{regions: regions}, provider_id),
    do: "#{provider_id} is not eligible for the #{Enum.join(regions, "/")} corridor"

  # ---- internals ---------------------------------------------------------

  defp rule_for(e, region), do: Map.get(e.regions, region, e.default)

  defp pairs([], [rule]), do: [{"default", rule}]
  defp pairs(regions, rules), do: Enum.zip(regions, rules)

  defp logs(rules) do
    local = Enum.filter(rules, & &1.local_only)
    chosen = if local == [], do: rules, else: local
    chosen |> Enum.flat_map(& &1.logs) |> Enum.uniq() |> Enum.sort()
  end

  defp provider_rule({:allow, list}), do: %{allow: list}
  defp provider_rule({:deny, list}), do: %{deny: list}

  defp to_atom(nil, default), do: default
  defp to_atom(value, _default) when is_atom(value), do: value

  defp to_atom(value, default),
    do: Enum.find(@instruments ++ @customer_types, default, &(Atom.to_string(&1) == value))

  defp parse_rule(raw, path) when is_map(raw) do
    raw = stringify(raw)
    unknown = for key <- Map.keys(raw) -- @rule_keys, do: "#{path}: unknown key #{key}"

    fields = [
      {:instruments, "instruments", &enum_list(&1, @instruments)},
      {:customer_types, "customer_types", &enum_list(&1, @customer_types)},
      {:providers, "providers", &parse_providers/1},
      {:logs, "logs", &parse_logs/1},
      {:local_only, "local_only", &parse_bool/1},
      {:retention_days, "retention_days", &parse_retention/1}
    ]

    {values, errors} =
      Enum.reduce(fields, {%{}, unknown}, fn {field, key, parse}, {vals, errs} ->
        with {:ok, value} <- Map.fetch(raw, key),
             {:error, message} <- parse.(value) do
          {vals, errs ++ ["#{path}.#{key}: #{message}"]}
        else
          :error -> {vals, errs}
          {:ok, parsed} -> {Map.put(vals, field, parsed), errs}
        end
      end)

    {struct!(Rule, values), errors}
  end

  defp parse_rule(_raw, path), do: {%Rule{}, ["#{path}: must be a map"]}

  defp enum_list(list, allowed) when is_list(list) and list != [] do
    parsed = Enum.map(list, fn v -> Enum.find(allowed, &(Atom.to_string(&1) == to_string(v))) end)

    if Enum.any?(parsed, &is_nil/1),
      do: {:error, "unknown value (expected some of #{inspect(allowed)})"},
      else: {:ok, parsed}
  end

  defp enum_list(_, _), do: {:error, "must be a non-empty list"}

  defp parse_providers("all"), do: {:ok, :all}

  defp parse_providers(%{} = map) do
    case stringify(map) do
      %{"allow" => list} when is_list(list) and map_size(map) == 1 ->
        {:ok, {:allow, Enum.map(list, &to_string/1)}}

      %{"deny" => list} when is_list(list) and map_size(map) == 1 ->
        {:ok, {:deny, Enum.map(list, &to_string/1)}}

      _ ->
        {:error, ~s(must be "all", {"allow": [...]} or {"deny": [...]})}
    end
  end

  defp parse_providers(_), do: {:error, ~s(must be "all", {"allow": [...]} or {"deny": [...]})}

  defp parse_logs([_ | _] = logs) do
    if Enum.all?(logs, &(is_binary(&1) and &1 =~ ~r/^[A-Za-z0-9_-]+$/)),
      do: {:ok, logs},
      else: {:error, "log names must be letters, digits, - or _"}
  end

  defp parse_logs(_), do: {:error, "must be a non-empty list of log names"}

  defp parse_bool(v) when is_boolean(v), do: {:ok, v}
  defp parse_bool(_), do: {:error, "must be true or false"}

  defp parse_retention(nil), do: {:ok, nil}
  defp parse_retention(v) when is_integer(v) and v > 0, do: {:ok, v}
  defp parse_retention(_), do: {:error, "must be nil or a positive number of days"}

  defp stringify(map), do: Map.new(map, fn {k, v} -> {to_string(k), v} end)
end

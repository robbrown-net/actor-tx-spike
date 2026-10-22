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
  `{"allow": [...]}`, or `{"deny": [...]}`), `currencies` (`"all"` or a list of codes such as
  `["INR", "USD"]`; both the source and the destination currency of a transaction must be listed),
  `logs` (default `["GLOBAL"]`),
  `local_only` (default false), `retention_days` (default none).
  """

  @instruments [:spot, :forward]
  @customer_types [:business, :retail]

  alias ActorTxSpike.Eligibility.{Parser, Rule}

  defstruct default: %Rule{}, regions: %{}
  @type t :: %__MODULE__{default: Rule.t(), regions: %{String.t() => Rule.t()}}

  @doc "Build from plain data (string or atom keys). Returns every error found."
  @spec new(map()) :: {:ok, t()} | {:error, [String.t()]}
  def new(data) when is_map(data) do
    with {:ok, {default, regions}} <- Parser.parse(data) do
      {:ok, %__MODULE__{default: default, regions: regions}}
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
    regions = regions_of(request)
    rules = rules_for(e, regions)
    instrument = to_atom(request[:instrument], :spot)
    customer = to_atom(request[:customer_type], :business)
    currencies = [request[:source_currency], request[:destination_currency]]
    reasons = violations(pairs(regions, rules), instrument, customer, currencies)

    %{
      permitted?: reasons == [],
      reasons: reasons,
      regions: regions,
      instrument: instrument,
      customer_type: customer,
      source_currency: request[:source_currency],
      destination_currency: request[:destination_currency],
      provider_rules: for(r <- rules, r.providers != :all, do: provider_rule(r.providers)),
      logs: logs(rules),
      retention_days: longest_retention(rules)
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

  defp regions_of(request) do
    for key <- [:origin_region, :destination_region],
        r = request[key],
        uniq: true,
        do: to_string(r)
  end

  defp rules_for(e, []), do: [e.default]
  defp rules_for(e, regions), do: Enum.map(regions, &rule_for(e, &1))

  defp rule_for(e, region), do: Map.get(e.regions, region, e.default)

  defp violations(pairs, instrument, customer, currencies) do
    Enum.flat_map(pairs, fn {region, rule} ->
      [
        forbidden(instrument, rule.instruments, "#{instrument} is not permitted in #{region}"),
        forbidden(
          customer,
          rule.customer_types,
          "#{customer} customers are not permitted in #{region}"
        )
        | Enum.map(currencies, &currency_forbidden(&1, rule.currencies, region))
      ]
      |> Enum.reject(&is_nil/1)
    end)
  end

  defp currency_forbidden(nil, _allowed, _region), do: nil
  defp currency_forbidden(_currency, :all, _region), do: nil

  defp currency_forbidden(currency, allowed, region),
    do: forbidden(currency, allowed, "#{currency} cannot be used in #{region}")

  defp forbidden(value, allowed, message), do: if(value in allowed, do: nil, else: message)

  defp longest_retention(rules) do
    rules |> Enum.map(& &1.retention_days) |> Enum.reject(&is_nil/1) |> Enum.max(fn -> nil end)
  end

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
end

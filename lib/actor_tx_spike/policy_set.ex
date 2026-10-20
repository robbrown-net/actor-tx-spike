defmodule ActorTxSpike.PolicySet do
  @moduledoc """
  Cascading routing objectives: **global → merchant → customer**, most specific
  wins, everything else inherited.

      PolicySet.new(%{
        "global"    => %{"weights" => %{"cost" => 0.6, "speed" => 0.3, "reliability" => 0.1}, "max_bps_from_best" => 100},
        "merchants" => %{"acme" => %{"overrides_allowed" => true, "policy" => %{"weights" => %{"cost" => 0.9}}}},
        "customers" => %{"vip-1" => %{"policy" => %{"weights" => %{"speed" => 0.8}}}}
      })

  * A merchant layer is honoured only when `overrides_allowed` is true (a paid
    privilege); otherwise it is ignored and the resolution says so.
  * Overrides are *partial*: unspecified fields are inherited. `weights` merge
    key by key (and are renormalised by `Policy`).
  * Every layer is validated up front by building the policy it produces, so a
    bad override is rejected when the set is built, not mid-transaction.
  """

  alias ActorTxSpike.{Policy, Validation}

  @enforce_keys [:global]
  defstruct [:global, merchants: %{}, customers: %{}]

  @type t :: %__MODULE__{}
  @type layer :: :global | {:merchant, String.t()} | {:customer, String.t()}

  @spec new(map()) :: {:ok, t()} | {:error, [String.t()]}
  def new(data) when is_map(data) do
    data = Validation.stringify(data)
    global = Map.get(data, "global", %{})
    merchants = data |> Map.get("merchants", %{}) |> Validation.stringify()
    customers = data |> Map.get("customers", %{}) |> Validation.stringify()

    set = %__MODULE__{
      global: Validation.stringify(global),
      merchants: merchants,
      customers: customers
    }

    errors =
      policy_errors("global", set.global) ++
        layer_errors("merchant", merchants, fn m -> merge(set.global, partial(m)) end) ++
        layer_errors("customer", customers, fn c -> merge(set.global, partial(c)) end)

    if errors == [], do: {:ok, set}, else: {:error, errors}
  end

  @doc """
  Resolve the policy for a request's `merchant_id` / `customer_id`.
  Returns the policy and the layers that contributed, in application order.
  """
  @spec resolve(t(), map()) :: {Policy.t(), [layer() | {:ignored, layer(), atom()}]}
  def resolve(%__MODULE__{} = set, request) do
    {data, layers} =
      {set.global, [:global]}
      |> layer(set.merchants, :merchant, request[:merchant_id], &entitled?/1)
      |> layer(set.customers, :customer, request[:customer_id], fn _entry -> true end)

    {:ok, policy} = Policy.new(data)
    {policy, layers}
  end

  defp layer(acc, _entries, _kind, nil, _entitled?), do: acc

  defp layer({data, layers}, entries, kind, id, entitled?) do
    id = to_string(id)

    case Map.get(entries, id) do
      nil -> {data, layers}
      entry -> contribute({data, layers}, entry, {kind, id}, entitled?.(entry))
    end
  end

  defp contribute({data, layers}, entry, layer, true),
    do: {merge(data, partial(entry)), layers ++ [layer]}

  defp contribute({data, layers}, _entry, layer, false),
    do: {data, layers ++ [{:ignored, layer, :not_entitled}]}

  @doc "Human-readable layer list, e.g. `\"global + merchant:acme + customer:vip-1\"`."
  @spec describe([layer() | tuple()]) :: String.t()
  def describe(layers) do
    Enum.map_join(layers, " + ", fn
      :global -> "global"
      {:merchant, id} -> "merchant:#{id}"
      {:customer, id} -> "customer:#{id}"
      {:ignored, {:merchant, id}, reason} -> "merchant:#{id} (ignored: #{reason})"
    end)
  end

  defp entitled?(entry), do: Validation.stringify(entry)["overrides_allowed"] == true

  defp partial(entry),
    do: entry |> Validation.stringify() |> Map.get("policy", %{}) |> Validation.stringify()

  defp merge(base, override) do
    weights =
      Map.merge(
        Validation.stringify(Map.get(base, "weights", %{})),
        Validation.stringify(Map.get(override, "weights", %{}))
      )

    merged = Map.merge(base, Map.delete(override, "weights"))
    if map_size(weights) == 0, do: merged, else: Map.put(merged, "weights", weights)
  end

  defp layer_errors(kind, entries, build) do
    Enum.flat_map(entries, fn {id, entry} -> policy_errors("#{kind}:#{id}", build.(entry)) end)
  end

  defp policy_errors(label, data) do
    case Policy.new(data) do
      {:ok, _} -> []
      {:error, errs} -> Enum.map(errs, &"#{label}: #{&1}")
    end
  end
end

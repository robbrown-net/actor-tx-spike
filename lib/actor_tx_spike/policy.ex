defmodule ActorTxSpike.Policy do
  @moduledoc """
  Routing/scoring policy as validated data.

  Build from plain data (atom or string keys, e.g. decoded JSON):

      Policy.new(%{
        "weights" => %{"cost" => 0.6, "speed" => 0.3, "reliability" => 0.1},
        "min_validity_ms" => 5_000,
        "max_latency_ms" => 2_000,
        "exclude_states" => ["stalled", "quarantined"]
      })

  * `weights`: relative importance of net cost to customer, response speed and
    provider reliability. Normalised to sum to 1; omitted weights are 0.
  * hard constraints: `min_validity_ms` (remaining quote life), `max_latency_ms`.
  * `exclude_states`: provider health states that are never called.

  Scoring is pure and deterministic. Cost and speed are scaled 0..1 *relative to
  the candidate set* (1 = best); reliability is a fixed factor per health state.
  """

  @keys ~w(weights min_validity_ms max_latency_ms exclude_states)
  @weight_keys ~w(cost speed reliability)
  @states [:unknown, :healthy, :degraded, :stalled, :quarantined]
  @reliability %{healthy: 1.0, unknown: 0.7, degraded: 0.4}

  defstruct weights: %{cost: 0.6, speed: 0.3, reliability: 0.1},
            min_validity_ms: 0,
            max_latency_ms: nil,
            exclude_states: [:stalled, :quarantined]

  @type t :: %__MODULE__{}

  @spec new(map()) :: {:ok, t()} | {:error, [String.t()]}
  def new(data) when is_map(data) do
    data = Map.new(data, fn {k, v} -> {to_string(k), v} end)
    unknown = for key <- Map.keys(data) -- @keys, do: "unknown key: #{key}"

    fields = [
      {:weights, "weights", &parse_weights/1},
      {:min_validity_ms, "min_validity_ms", &parse_min_validity/1},
      {:max_latency_ms, "max_latency_ms", &parse_max_latency/1},
      {:exclude_states, "exclude_states", &parse_states/1}
    ]

    {values, errors} =
      Enum.reduce(fields, {%{}, unknown}, fn {field, key, parse}, {values, errors} ->
        with {:ok, raw} <- Map.fetch(data, key),
             {:error, message} <- parse.(raw) do
          {values, errors ++ ["#{key}: #{message}"]}
        else
          :error -> {values, errors}
          {:ok, value} -> {Map.put(values, field, value), errors}
        end
      end)

    if errors == [], do: {:ok, struct!(__MODULE__, values)}, else: {:error, errors}
  end

  @doc "Is a provider in this health state excluded from calling?"
  @spec exclude_state?(t(), atom()) :: boolean()
  def exclude_state?(%__MODULE__{} = p, state), do: state == :down or state in p.exclude_states

  @doc "Hard constraints on a candidate's `validity_ms` and `latency_ms`."
  @spec check(t(), %{validity_ms: integer(), latency_ms: integer()}) ::
          :ok | {:excluded, :quote_expires_too_soon | :too_slow}
  def check(%__MODULE__{} = p, %{validity_ms: validity, latency_ms: latency}) do
    cond do
      validity < p.min_validity_ms -> {:excluded, :quote_expires_too_soon}
      p.max_latency_ms != nil and latency > p.max_latency_ms -> {:excluded, :too_slow}
      true -> :ok
    end
  end

  @doc "Scores (higher is better) for candidates with `:net`, `:latency_ms`, `:state`."
  @spec scores(t(), [map()]) :: [float()]
  def scores(%__MODULE__{}, []), do: []

  def scores(%__MODULE__{weights: w}, candidates) do
    nets = Enum.map(candidates, & &1.net)
    latencies = Enum.map(candidates, & &1.latency_ms)

    Enum.map(candidates, fn c ->
      w.cost * scale(c.net, nets, :higher_better) +
        w.speed * scale(c.latency_ms, latencies, :lower_better) +
        w.reliability * Map.get(@reliability, c.state, 0.0)
    end)
  end

  defp scale(value, all, direction) do
    {min, max} = Enum.min_max(all)

    cond do
      max == min -> 1.0
      direction == :higher_better -> (value - min) / (max - min)
      true -> (max - value) / (max - min)
    end
  end

  defp parse_weights(raw) when is_map(raw) do
    raw = Map.new(raw, fn {k, v} -> {to_string(k), v} end)
    extra = Map.keys(raw) -- @weight_keys
    values = Enum.map(@weight_keys, &Map.get(raw, &1, 0))

    cond do
      extra != [] ->
        {:error, "unknown weights: #{Enum.join(extra, ", ")}"}

      not Enum.all?(values, &(is_number(&1) and &1 >= 0)) ->
        {:error, "must be non-negative numbers"}

      Enum.sum(values) == 0 ->
        {:error, "must not all be zero"}

      true ->
        total = Enum.sum(values)
        [cost, speed, reliability] = Enum.map(values, &(&1 / total))
        {:ok, %{cost: cost, speed: speed, reliability: reliability}}
    end
  end

  defp parse_weights(_), do: {:error, "must be a map"}

  defp parse_min_validity(v) when is_integer(v) and v >= 0, do: {:ok, v}
  defp parse_min_validity(_), do: {:error, "must be a non-negative integer"}

  defp parse_max_latency(nil), do: {:ok, nil}
  defp parse_max_latency(v) when is_integer(v) and v > 0, do: {:ok, v}
  defp parse_max_latency(_), do: {:error, "must be nil or a positive integer"}

  defp parse_states(list) when is_list(list) do
    parsed = Enum.map(list, fn s -> Enum.find(@states, &(to_string(&1) == to_string(s))) end)

    if Enum.any?(parsed, &is_nil/1),
      do: {:error, "unknown state (expected one of #{inspect(@states)})"},
      else: {:ok, parsed}
  end

  defp parse_states(_), do: {:error, "must be a list"}
end

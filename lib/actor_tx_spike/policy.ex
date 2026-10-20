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
  * `max_bps_from_best`: never accept a quote whose net value is more than this
    many basis points worse than the best quote received (nil = no limit).
    Applied before scoring, so speed/reliability can never "buy" an
    unacceptably bad price.
  * `exclude_states`: provider health states that are never called.

  Scoring is pure and deterministic. Cost and speed are scaled 0..1 *relative to
  the candidate set* (1 = best); reliability is a fixed factor per health state.
  """

  alias ActorTxSpike.Validation

  @keys ~w(weights min_validity_ms max_latency_ms max_bps_from_best exclude_states)
  @weight_keys ~w(cost speed reliability)
  @states [:unknown, :healthy, :degraded, :stalled, :quarantined]
  @reliability %{healthy: 1.0, unknown: 0.7, degraded: 0.4}

  defstruct weights: %{cost: 0.6, speed: 0.3, reliability: 0.1},
            min_validity_ms: 0,
            max_latency_ms: nil,
            max_bps_from_best: nil,
            exclude_states: [:stalled, :quarantined]

  @type t :: %__MODULE__{}

  @spec new(map()) :: {:ok, t()} | {:error, [String.t()]}
  def new(data) when is_map(data) do
    data = Validation.stringify(data)
    unknown = for key <- Map.keys(data) -- @keys, do: "unknown key: #{key}"
    {values, errors} = Validation.parse_fields(data, fields(), "", unknown)

    if errors == [], do: {:ok, struct!(__MODULE__, values)}, else: {:error, errors}
  end

  defp fields do
    [
      {:weights, "weights", &parse_weights/1},
      {:min_validity_ms, "min_validity_ms", &parse_min_validity/1},
      {:max_latency_ms, "max_latency_ms", &parse_max_latency/1},
      {:max_bps_from_best, "max_bps_from_best", &parse_max_bps/1},
      {:exclude_states, "exclude_states", &parse_states/1}
    ]
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
      too_slow?(p, latency) -> {:excluded, :too_slow}
      true -> :ok
    end
  end

  defp too_slow?(%__MODULE__{max_latency_ms: nil}, _latency), do: false
  defp too_slow?(%__MODULE__{max_latency_ms: max}, latency), do: latency > max

  @doc """
  Split candidates (with `:net`) into those within `max_bps_from_best` of the
  best net value and those outside it, as `{kept, [{candidate, bps_worse}]}`.
  """
  @spec within_best(t(), [map()]) :: {[map()], [{map(), float()}]}
  def within_best(%__MODULE__{max_bps_from_best: nil}, candidates), do: {candidates, []}
  def within_best(%__MODULE__{}, []), do: {[], []}

  def within_best(%__MODULE__{max_bps_from_best: max}, candidates) do
    best = candidates |> Enum.map(& &1.net) |> Enum.max()

    {kept, dropped} =
      candidates
      |> Enum.map(fn c -> {c, if(best > 0, do: (best - c.net) / best * 10_000, else: 0.0)} end)
      |> Enum.split_with(fn {_c, bps} -> bps <= max end)

    {Enum.map(kept, &elem(&1, 0)), dropped}
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
    raw = Validation.stringify(raw)
    values = Enum.map(@weight_keys, &Map.get(raw, &1, 0))

    with :ok <- known_weights(Map.keys(raw) -- @weight_keys),
         :ok <- usable_weights(values) do
      [cost, speed, reliability] = Enum.map(values, &(&1 / Enum.sum(values)))
      {:ok, %{cost: cost, speed: speed, reliability: reliability}}
    end
  end

  defp parse_weights(_), do: {:error, "must be a map"}

  defp known_weights([]), do: :ok
  defp known_weights(extra), do: {:error, "unknown weights: #{Enum.join(extra, ", ")}"}

  defp non_negative?(value), do: is_number(value) and value >= 0

  defp usable_weights(values) do
    cond do
      not Enum.all?(values, &non_negative?/1) ->
        {:error, "must be non-negative numbers"}

      Enum.sum(values) == 0 ->
        {:error, "must not all be zero"}

      true ->
        :ok
    end
  end

  defp parse_min_validity(v) when is_integer(v) and v >= 0, do: {:ok, v}
  defp parse_min_validity(_), do: {:error, "must be a non-negative integer"}

  defp parse_max_latency(nil), do: {:ok, nil}
  defp parse_max_latency(v) when is_integer(v) and v > 0, do: {:ok, v}
  defp parse_max_latency(_), do: {:error, "must be nil or a positive integer"}

  defp parse_max_bps(nil), do: {:ok, nil}
  defp parse_max_bps(v) when is_number(v) and v >= 0, do: {:ok, v}
  defp parse_max_bps(_), do: {:error, "must be nil or a non-negative number"}

  defp parse_states(list) when is_list(list) do
    parsed = Enum.map(list, fn s -> Enum.find(@states, &(to_string(&1) == to_string(s))) end)

    if Enum.any?(parsed, &is_nil/1),
      do: {:error, "unknown state (expected one of #{inspect(@states)})"},
      else: {:ok, parsed}
  end

  defp parse_states(_), do: {:error, "must be a list"}
end

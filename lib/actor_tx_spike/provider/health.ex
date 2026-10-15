defmodule ActorTxSpike.Provider.Health do
  @moduledoc """
  Pure model of our *view* of a provider's health. No processes, no clocks:
  time is always passed in, so every transition is deterministic and testable.

  Derived state:

  * `:unknown`     no observations yet
  * `:healthy`     recent calls fine
  * `:degraded`    elevated error rate, high latency, probing, or still recovering
  * `:stalled`     circuit open: consecutive failures hit the threshold
  * `:quarantined` behaviour changed (version drift); needs a human/sentinel release

  Circuit breaker: `:closed` -> (N consecutive failures) -> `:open` -> (cool-down)
  -> `:half_open` (one probe call admitted) -> `:closed` on success, `:open` on failure.

  Recovery is gradual: after a successful probe the breaker closes and history
  is cleared, but the provider stays `:degraded` until `recovery_successes`
  consecutive successes (the probe counts as one). A failure while recovering
  restarts the count. Sentinel probes (Phase 3) feed this for quiet providers.
  """

  @outcomes [:success, :rejected, :error, :timeout]
  @failures [:error, :timeout]

  defstruct version: nil,
            drift: nil,
            quarantined: false,
            breaker: :closed,
            probing: false,
            opened_at: nil,
            consecutive_failures: 0,
            window: [],
            window_size: 20,
            failure_threshold: 5,
            cool_down_ms: 30_000,
            degraded_error_rate: 0.2,
            slow_ms: 2_000,
            recovery_successes: 3,
            recovering: 0

  @type outcome :: :success | :rejected | :error | :timeout
  @type t :: %__MODULE__{}

  @spec new(keyword()) :: t()
  def new(opts \\ []), do: struct!(__MODULE__, opts)

  @doc "May a call be made now? Admitting a half-open probe is a state change."
  @spec checkout(t(), integer()) :: {:ok, t()} | {:error, :quarantined | :circuit_open}
  def checkout(%__MODULE__{quarantined: true}, _now), do: {:error, :quarantined}
  def checkout(%__MODULE__{breaker: :closed} = h, _now), do: {:ok, h}

  def checkout(%__MODULE__{breaker: :open, opened_at: at, cool_down_ms: cd} = h, now)
      when now - at >= cd,
      do: {:ok, %{h | breaker: :half_open, probing: true}}

  def checkout(%__MODULE__{breaker: :open}, _now), do: {:error, :circuit_open}

  def checkout(%__MODULE__{breaker: :half_open, probing: false} = h, _now),
    do: {:ok, %{h | probing: true}}

  def checkout(%__MODULE__{breaker: :half_open}, _now), do: {:error, :circuit_open}

  @doc "Record the outcome and latency of a completed call."
  @spec record(t(), outcome(), non_neg_integer(), integer()) :: t()
  def record(%__MODULE__{} = h, outcome, latency_ms, now)
      when outcome in @outcomes and is_integer(latency_ms) do
    window = Enum.take([{outcome, latency_ms} | h.window], h.window_size)
    advance_breaker(%{h | window: window}, outcome in @failures, now)
  end

  @doc "A probe call was admitted but the caller vanished: free the probe slot."
  @spec abandon(t()) :: t()
  def abandon(%__MODULE__{} = h), do: %{h | probing: false}

  @spec state(t()) :: :unknown | :healthy | :degraded | :stalled | :quarantined
  def state(%__MODULE__{quarantined: true}), do: :quarantined
  def state(%__MODULE__{window: [], breaker: :closed}), do: :unknown
  def state(%__MODULE__{breaker: :open}), do: :stalled
  def state(%__MODULE__{breaker: :half_open}), do: :degraded
  def state(%__MODULE__{recovering: n}) when n > 0, do: :degraded

  def state(%__MODULE__{} = h) do
    stats = stats(h)

    if stats.error_rate >= h.degraded_error_rate or stats.p95_ms >= h.slow_ms,
      do: :degraded,
      else: :healthy
  end

  @spec stats(t()) :: %{
          samples: non_neg_integer(),
          error_rate: float(),
          mean_ms: number(),
          p95_ms: number()
        }
  def stats(%__MODULE__{window: []}), do: %{samples: 0, error_rate: 0.0, mean_ms: 0, p95_ms: 0}

  def stats(%__MODULE__{window: window}) do
    n = length(window)
    failures = Enum.count(window, fn {outcome, _} -> outcome in @failures end)
    latencies = window |> Enum.map(&elem(&1, 1)) |> Enum.sort()

    %{
      samples: n,
      error_rate: failures / n,
      mean_ms: Enum.sum(latencies) / n,
      p95_ms: Enum.at(latencies, ceil(0.95 * n) - 1)
    }
  end

  @doc "Observe a version reported by a probe. A change quarantines the provider."
  @spec observe_version(t(), String.t()) ::
          {t(), :first_seen | :unchanged | {:drift, String.t(), String.t()}}
  def observe_version(%__MODULE__{version: nil} = h, version),
    do: {%{h | version: version}, :first_seen}

  def observe_version(%__MODULE__{version: version} = h, version), do: {h, :unchanged}

  def observe_version(%__MODULE__{version: old} = h, new),
    do: {%{h | version: new, quarantined: true, drift: {old, new}}, {:drift, old, new}}

  @doc "Lift quarantine after the drift has been reviewed."
  @spec release(t()) :: t()
  def release(%__MODULE__{} = h), do: %{h | quarantined: false, drift: nil}

  defp advance_breaker(%__MODULE__{breaker: :half_open} = h, true, now), do: open(h, now)

  # Probe succeeded: close, forget pre-outage history, and require a streak before :healthy.
  defp advance_breaker(%__MODULE__{breaker: :half_open} = h, false, _now),
    do: %{
      h
      | breaker: :closed,
        probing: false,
        consecutive_failures: 0,
        opened_at: nil,
        window: Enum.take(h.window, 1),
        recovering: max(h.recovery_successes - 1, 0)
    }

  defp advance_breaker(%__MODULE__{breaker: :open} = h, _failed?, _now), do: h

  defp advance_breaker(%__MODULE__{} = h, false, _now),
    do: %{h | consecutive_failures: 0, recovering: max(h.recovering - 1, 0)}

  defp advance_breaker(%__MODULE__{} = h, true, now) do
    recovering = if h.recovering > 0, do: h.recovery_successes, else: 0
    h = %{h | consecutive_failures: h.consecutive_failures + 1, recovering: recovering}
    if h.consecutive_failures >= h.failure_threshold, do: open(h, now), else: h
  end

  defp open(h, now), do: %{h | breaker: :open, probing: false, opened_at: now}
end

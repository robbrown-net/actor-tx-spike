defmodule ActorTxSpike.Cutoff do
  @moduledoc """
  A regional settlement cut-off: "no later than 18:00 UK local time".

  Time zones are the dangerous part of this domain, so the model is explicit:

  * `new/1` takes a local `hour`/`minute` and a **UTC offset in minutes**
    (a fixed offset, or a function `Date.t() -> minutes` so the caller can
    supply DST rules from a timezone database without this project taking a
    runtime dependency on one).
  * `fixed/2` is an absolute instant (used by tests and the demo).

  `next_ms/2` answers "when is the next occurrence of this cut-off, in UTC ms,
  strictly after `now_ms`?".
  """

  @enforce_keys [:region]
  alias ActorTxSpike.Validation

  defstruct [:region, :hour, :minute, :offset, :at_ms]

  @type t :: %__MODULE__{}

  @spec new(map()) :: {:ok, t()} | {:error, [String.t()]}
  def new(data) when is_map(data) do
    data = Validation.stringify(data)
    region = Map.get(data, "region")
    hour = Map.get(data, "hour")
    minute = Map.get(data, "minute", 0)
    offset = Map.get(data, "utc_offset_minutes")

    Validation.build(%__MODULE__{region: region, hour: hour, minute: minute, offset: offset}, [
      {Validation.present?(region), "region: required"},
      {Validation.in_range?(hour, 0..23), "hour: must be 0..23"},
      {Validation.in_range?(minute, 0..59), "minute: must be 0..59"},
      {valid_offset?(offset),
       "utc_offset_minutes: must be an integer in -840..840 or a function of a Date"}
    ])
  end

  @doc "An absolute-instant cut-off (UTC milliseconds)."
  @spec fixed(String.t(), integer()) :: t()
  def fixed(region, at_ms), do: %__MODULE__{region: region, at_ms: at_ms}

  @doc "The next occurrence of the cut-off strictly after `now_ms`, in UTC milliseconds."
  @spec next_ms(t(), integer()) :: integer()
  def next_ms(%__MODULE__{at_ms: at}, _now_ms) when is_integer(at), do: at

  def next_ms(%__MODULE__{} = c, now_ms) do
    today = now_ms |> DateTime.from_unix!(:millisecond) |> DateTime.to_date()

    [today, Date.add(today, -1), Date.add(today, 1), Date.add(today, 2)]
    |> Enum.sort(Date)
    |> Enum.map(&utc_ms(c, &1))
    |> Enum.find(&(&1 > now_ms))
  end

  @doc "Milliseconds until the cut-off (negative once passed). Fixed instants only count down."
  @spec remaining_ms(t(), integer()) :: integer()
  def remaining_ms(%__MODULE__{} = c, now_ms), do: next_ms(c, now_ms) - now_ms

  defp utc_ms(c, date) do
    local = NaiveDateTime.new!(date, Time.new!(c.hour, c.minute, 0))
    local_ms = NaiveDateTime.diff(local, ~N[1970-01-01 00:00:00], :millisecond)
    local_ms - offset_minutes(c.offset, date) * 60_000
  end

  defp offset_minutes(fun, date) when is_function(fun, 1), do: fun.(date)
  defp offset_minutes(minutes, _date), do: minutes

  defp valid_offset?(o) when is_integer(o), do: o in -840..840
  defp valid_offset?(o), do: is_function(o, 1)
end

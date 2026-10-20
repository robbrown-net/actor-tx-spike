defmodule ActorTxSpike.Replay.Compare do
  @moduledoc """
  Compare two days, for example this system against another running in
  parallel, matched by transaction id.

  Per transaction it compares the outcome, the provider the payment went
  through, the net value achieved and the steps completed. Timing is
  deliberately not compared: two systems never agree to the millisecond.
  """

  alias ActorTxSpike.Replay.{Day, Tx}

  @fields [:outcome, :provider, :net, :steps]

  @spec diff(Day.t(), Day.t()) :: map()
  def diff(%Day{} = a, %Day{} = b) do
    only_a = missing_from(a, b)
    common = a.tx_order -- only_a
    divergences = divergences(a, b, common)

    %{
      common: length(common),
      identical: length(common) - (divergences |> Enum.map(& &1.tx) |> Enum.uniq() |> length()),
      only_a: only_a,
      only_b: missing_from(b, a),
      divergences: divergences
    }
  end

  # Transactions `day` has that `other` does not, in arrival order.
  defp missing_from(day, other) do
    known = MapSet.new(other.tx_order)
    Enum.reject(day.tx_order, &MapSet.member?(known, &1))
  end

  defp divergences(a, b, ids) do
    for id <- ids,
        field <- @fields,
        {x, y} = {value(a, id, field), value(b, id, field)},
        x != y do
      %{tx: id, field: field, a: x, b: y}
    end
  end

  defp value(day, id, field), do: day.by_tx[id] |> Tx.summarize() |> Map.fetch!(field)
end

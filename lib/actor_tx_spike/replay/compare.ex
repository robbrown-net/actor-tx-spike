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
    ids_a = MapSet.new(a.tx_order)
    ids_b = MapSet.new(b.tx_order)
    common = for id <- a.tx_order, MapSet.member?(ids_b, id), do: id

    divergences =
      for id <- common,
          field <- @fields,
          {x, y} = {value(a, id, field), value(b, id, field)},
          x != y do
        %{tx: id, field: field, a: x, b: y}
      end

    %{
      common: length(common),
      identical: length(common) - (divergences |> Enum.map(& &1.tx) |> Enum.uniq() |> length()),
      only_a: for(id <- a.tx_order, not MapSet.member?(ids_b, id), do: id),
      only_b: for(id <- b.tx_order, not MapSet.member?(ids_a, id), do: id),
      divergences: divergences
    }
  end

  defp value(day, id, field), do: day.by_tx[id] |> Tx.summarize() |> Map.fetch!(field)
end

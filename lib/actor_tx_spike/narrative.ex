defmodule ActorTxSpike.Narrative do
  @moduledoc """
  Turns the event stream into a story a human can follow in a terminal.

      12:03:44.120  tx-7  payment        transaction received: amount 1000
      12:03:44.121  tx-7  selector       asking 5 provider(s) for quotes ...
      12:03:44.125  tx-7  provider:alpha received a quote request for 1000
  """

  alias ActorTxSpike.Events.Event

  @spec format(Event.t(), keyword()) :: String.t()
  def format(%Event{} = e, opts \\ []) do
    time =
      e.at_ms
      |> DateTime.from_unix!(:millisecond)
      |> Calendar.strftime("%H:%M:%S.%f")
      |> binary_part(0, 12)

    tx = String.pad_trailing(e.tx_id || "-", 10)
    actor = e.actor |> actor_label() |> String.pad_trailing(18)
    "#{time}  #{tx} #{colour(actor, e.actor, opts)} #{e.message}"
  end

  @spec actor_label(term()) :: String.t()
  def actor_label({:provider, id}), do: "provider:#{id}"
  def actor_label(other), do: to_string(other)

  defp colour(text, actor, opts) do
    if opts[:color],
      do: [color_for(actor), text, :reset] |> IO.ANSI.format(true) |> IO.iodata_to_binary(),
      else: text
  end

  defp color_for(:payment), do: :cyan
  defp color_for(:selector), do: :magenta
  defp color_for(:sentinel), do: :yellow
  defp color_for({:provider, _}), do: :green
  defp color_for(_), do: :white
end

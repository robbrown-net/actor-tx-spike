defmodule ActorTxSpike.Poll do
  @moduledoc """
  Wait for something to become true, by asking again until it is or we give up.
  The one place the system sleeps-and-retries, so every caller waits the same way.
  """

  @doc """
  Call `fun` until it returns something truthy (then `true`) or `:tries` calls
  have been made `:every_ms` apart (then `false`).
  """
  @spec until((-> as_boolean(term())), keyword()) :: boolean()
  def until(fun, opts \\ []) do
    poll(fun, Keyword.get(opts, :tries, 300), Keyword.get(opts, :every_ms, 10))
  end

  defp poll(fun, tries, every_ms), do: fun.() |> verdict(fun, tries, every_ms)

  defp verdict(result, _fun, _tries, _every_ms) when result not in [false, nil], do: true
  defp verdict(_result, _fun, 0, _every_ms), do: false

  defp verdict(_result, fun, tries, every_ms) do
    Process.sleep(every_ms)
    poll(fun, tries - 1, every_ms)
  end
end

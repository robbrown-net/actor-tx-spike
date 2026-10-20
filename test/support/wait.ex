defmodule ActorTxSpike.Wait do
  @moduledoc "Test helper: wait for a condition, failing the test with a message if it never holds."
  import ExUnit.Assertions, only: [flunk: 1]

  alias ActorTxSpike.Poll

  @doc "Wait until `fun` is truthy. Options: `:tries`, `:every_ms` (see `Poll.until/2`), `:message`."
  @spec until!((-> as_boolean(term())), keyword()) :: :ok
  def until!(fun, opts \\ []) do
    if Poll.until(fun, opts),
      do: :ok,
      else: flunk(Keyword.get(opts, :message, "condition not met"))
  end
end

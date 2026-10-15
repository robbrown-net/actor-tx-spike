defmodule ActorTxSpike.Selection do
  @moduledoc """
  Result of a quote-and-select round.

  * `outcome`: `{:selected, candidate}`, `{:fallback, baseline}` (nothing usable
    arrived, caller-supplied baseline returned verbatim) or `:none`.
  * `ranked`: all admissible candidates, best first.
  * `excluded`: `{provider_id, reason}` for every provider not ranked.
  """

  defmodule Candidate do
    @moduledoc "An admissible, scored quote."
    @enforce_keys [:provider_id, :server, :quote, :net, :latency_ms, :validity_ms, :state]
    defstruct [:provider_id, :server, :quote, :net, :latency_ms, :validity_ms, :state, score: 0.0]
  end

  defstruct [:outcome, ranked: [], excluded: [], elapsed_ms: 0]

  @type t :: %__MODULE__{}
end

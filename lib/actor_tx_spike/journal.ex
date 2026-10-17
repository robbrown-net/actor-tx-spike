defmodule ActorTxSpike.Journal do
  @moduledoc """
  The machine-replayable half of the day's record.

  The narrative (`ActorTxSpike.Events`) tells a human what happened. The
  journal adds, in the *same* stream and therefore the same hash-chained audit
  log, everything a machine needs to replay the day: every **input** from the
  outside world (requests, provider answers with their latency, operator
  actions, health observations) and enough context to judge every **decision**
  again (the policy, guarantee, cut-off and settings in force).

  Journal events carry the actor `:journal`; the dashboard and terminal hide
  them, the audit log keeps them.

  ## Schema (v1)

  | kind | emitted by | carries |
  |---|---|---|
  | `tx_received` | Payment | `request`, `policy`, `guarantee`, `cutoff`, `config`, `providers` |
  | `quote_observed` | Selector | per provider asked: `outcome` (quote, rejected, error, timeout), `rate`, `fee`, `expires_at_ms`, `state`, `latency_ms`, `observed_at_ms` |
  | `provider_response` | Payment | per provider call: `op`, `key`, `attempt`, `class`, `reason`, `latency_ms` |
  | `control` | Demo | an operator or world action and its arguments |

  Together with the existing narrative kinds (`selection_made`, `step_*`,
  `provider_chosen`, `failover`, `undone`, `tx_completed`, `tx_economics`, and
  the provider `health` events) this is the replay script.
  """

  alias ActorTxSpike.Events

  @actor :journal

  @doc "Record a journal entry."
  @spec record(String.t() | nil, atom(), String.t(), map()) :: :ok
  def record(tx_id, kind, message, data), do: Events.emit(tx_id, @actor, kind, message, data)

  @doc "Is this event actor the journal (hidden from humans, kept in the log)?"
  @spec journal?(term()) :: boolean()
  def journal?(actor), do: actor == @actor or actor == "journal"
end

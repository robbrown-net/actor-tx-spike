defmodule ActorTxSpike.Provider do
  @moduledoc """
  Adapter behaviour for a third-party provider facade (FX quote + payment).

  We do not control providers: they may be fast, slow, expensive, degraded or
  stalled, and may change behaviour without notice. Adapters normalise every
  provider response into the small result vocabulary below so the rest of the
  system can reason about outcomes uniformly.

  * `{:ok, value}`       provider answered successfully
  * `{:rejected, why}`   provider answered "no" (a business answer, not a fault)
  * `{:error, reason}`   technical failure; nothing happened, safe to retry
  * `:timeout`           no answer within budget (effect unknown)
  * `:in_doubt`          effectful op only: the effect may have happened
  * `:not_found` / `:unknown`  `status` only

  ## Lifecycle operations

  `request_quote` has no side effect. The effectful steps are
  `accept_quote` (lock the rate), `authorise` (hold funds) and `pay`. Each
  takes an idempotency `key` and must be idempotent for it. Each has an undo
  keyed by the *same* key: `cancel_accept`, `void_authorisation`, `refund`.
  Undos must also be idempotent, and must be safe to call for a key whose
  effect never happened (the provider should remember it was cancelled).

  `status(config, op, key)` looks up what a provider holds for an effect. It
  is allowed to be slow or stale: callers must treat `:not_found` as
  "not known yet", not "did not happen".
  """

  alias ActorTxSpike.Provider.Quote

  @type config :: term()
  @type request :: %{required(:id) => String.t(), optional(atom()) => term()}
  @type key :: String.t()
  @type receipt :: map()
  @type effect_op :: :accept_quote | :authorise | :pay
  @type undo_op :: :cancel_accept | :void_authorisation | :refund
  @type probe_info :: %{required(:version) => String.t(), optional(atom()) => term()}

  @type effect_result ::
          {:ok, receipt} | {:rejected, term()} | {:error, term()} | :timeout | :in_doubt
  @type undo_result :: {:ok, receipt} | {:rejected, term()} | {:error, term()} | :timeout

  @callback request_quote(config, request) ::
              {:ok, Quote.t()} | {:rejected, term()} | {:error, term()} | :timeout

  @callback accept_quote(config, Quote.t(), key) :: effect_result
  @callback authorise(config, Quote.t(), key) :: effect_result
  @callback pay(config, Quote.t(), key) :: effect_result

  @callback cancel_accept(config, key) :: undo_result
  @callback void_authorisation(config, key) :: undo_result
  @callback refund(config, key) :: undo_result

  @callback status(config, effect_op | undo_op, key) ::
              {:ok, receipt} | :not_found | :unknown | {:error, term()} | :timeout

  @callback probe(config) :: {:ok, probe_info} | {:error, term()} | :timeout
end

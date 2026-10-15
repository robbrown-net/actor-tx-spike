defmodule ActorTxSpike.Provider do
  @moduledoc """
  Adapter behaviour for a third-party provider facade (FX quote + payment).

  We do not control providers: they may be fast, slow, expensive, degraded or
  stalled, and may change behaviour without notice. Adapters normalise every
  provider response into the small result vocabulary below so the rest of the
  system can reason about outcomes uniformly.

  * `{:ok, value}`       provider answered successfully
  * `{:rejected, why}`   provider answered "no" (a business answer, not a fault)
  * `{:error, reason}`   transient/technical failure; safe to retry elsewhere
  * `:timeout`           no answer within budget (effect unknown for `execute`)
  * `:in_doubt`          `execute` only: the effect may have happened
  * `:not_found` / `:unknown`  `status` only

  Callbacks take an adapter-specific `config` as the first argument. `execute`
  must be idempotent for a given `idempotency_key`.
  """

  alias ActorTxSpike.Provider.Quote

  @type config :: term()
  @type request :: %{required(:id) => String.t(), optional(atom()) => term()}
  @type key :: String.t()
  @type receipt :: map()
  @type probe_info :: %{required(:version) => String.t(), optional(atom()) => term()}

  @callback request_quote(config, request) ::
              {:ok, Quote.t()} | {:rejected, term()} | {:error, term()} | :timeout

  @callback execute(config, Quote.t(), key) ::
              {:ok, receipt} | {:rejected, term()} | {:error, term()} | :timeout | :in_doubt

  @callback status(config, key) ::
              {:ok, receipt} | :not_found | :unknown | {:error, term()} | :timeout

  @callback probe(config) :: {:ok, probe_info} | {:error, term()} | :timeout
end

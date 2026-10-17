defmodule ActorTxSpike.Provider.Quote do
  @moduledoc "A firm provider quote. It is only valid until `expires_at_ms` (system time)."

  @enforce_keys [:provider, :request_id, :rate, :fee, :expires_at_ms]
  defstruct [:provider, :request_id, :rate, :fee, :expires_at_ms]

  @type t :: %__MODULE__{
          provider: term(),
          request_id: String.t(),
          rate: ActorTxSpike.Money.rate(),
          fee: ActorTxSpike.Money.minor(),
          expires_at_ms: integer()
        }

  @spec expired?(t(), integer()) :: boolean()
  def expired?(%__MODULE__{expires_at_ms: at}, now_ms), do: now_ms >= at
end

defmodule ActorTxSpike.Eligibility.Rule do
  @moduledoc "The constraints one region imposes (see `ActorTxSpike.Eligibility`)."
  defstruct instruments: [:spot, :forward],
            customer_types: [:business, :retail],
            providers: :all,
            logs: ["GLOBAL"],
            local_only: false,
            retention_days: nil

  @type t :: %__MODULE__{}
end

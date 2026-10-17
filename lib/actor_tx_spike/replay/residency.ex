defmodule ActorTxSpike.Replay.Residency do
  @moduledoc """
  Data residency, proven from the logs themselves.

  Each transaction records, in its `tx_received`, the logs it must be written
  to (decided by `ActorTxSpike.Eligibility`). Given the regional logs loaded as
  days, this checks that every transaction appears in exactly those logs: not in
  one where it must not be (a leak), and not missing from one where it must be.
  """

  alias ActorTxSpike.Replay.{Day, Tx}

  @spec check(%{String.t() => Day.t()}) :: %{
          transactions: non_neg_integer(),
          violations: [map()],
          ok?: boolean()
        }
  def check(days) when is_map(days) do
    ids = days |> Map.values() |> Enum.flat_map(& &1.tx_order) |> Enum.uniq() |> Enum.sort()

    violations =
      for id <- ids, v <- check_tx(id, days), do: v

    %{transactions: length(ids), violations: violations, ok?: violations == []}
  end

  defp check_tx(id, days) do
    present = for {log, day} <- days, Map.has_key?(day.by_tx, id), do: log
    expected = expected_logs(id, days, present)

    leaks =
      for log <- present -- expected,
          do: %{
            rule: :residency_leak,
            tx: id,
            log: log,
            detail:
              "#{id} is recorded in #{log}, which it must not be (expected #{inspect(expected)})"
          }

    missing =
      for log <- expected -- present,
          do: %{
            rule: :residency_missing,
            tx: id,
            log: log,
            detail: "#{id} is missing from #{log}, where it must be recorded"
          }

    leaks ++ missing
  end

  # The decision is read from the transaction's own first record, in any log that has it.
  defp expected_logs(id, days, present) do
    present
    |> Enum.find_value(fn log -> Tx.first(days[log].by_tx[id], "tx_received") end)
    |> case do
      %{"data" => %{"eligibility" => %{"logs" => logs}}} -> logs
      _ -> present
    end
  end
end

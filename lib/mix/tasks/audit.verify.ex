defmodule Mix.Tasks.Audit.Verify do
  @shortdoc "Verify an audit log's hash chain"
  @moduledoc """
  Checks every record of an audit log: sequence, chain links and hashes.

      mix audit.verify audit/demo.log

  Prints the record count and the head hash (anchor it somewhere independent
  to also detect truncation) and exits non-zero on the first broken record.
  """
  use Mix.Task

  @impl true
  def run([path]) do
    case ActorTxSpike.Audit.verify(path) do
      {:ok, %{records: n, head: head}} ->
        Mix.shell().info("OK: #{n} records, head #{head}")

      {:error, {:line, line, why}} ->
        Mix.raise("audit log is NOT intact: line #{line} (#{why})")
    end
  end

  def run(_), do: Mix.raise("usage: mix audit.verify PATH")
end

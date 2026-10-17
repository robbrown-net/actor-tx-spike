defmodule ActorTxSpike.Audit.Lock do
  @moduledoc """
  One writer per audit log.

  A hash chain has exactly one head. Two processes appending to the same file
  (a second instance started by mistake, or a restart that overlaps a dying
  node) interleave records and break the chain for good. So the writer takes a
  lock file, `<log>.lock`, naming its OS process and its Erlang process.

  A lock whose owner is gone is stale and is taken over (a crashed or killed
  node never releases it): the owner is gone if its OS process is not running,
  or if it is this very VM and the Erlang process is dead. A lock whose owner is
  alive refuses the new writer.
  """

  @doc "Take the lock for `path`, or report who holds it."
  @spec acquire(Path.t()) :: :ok | {:error, {:audit_locked, String.t()}}
  def acquire(path) do
    lock = path <> ".lock"
    File.mkdir_p!(Path.dirname(path))

    case File.open(lock, [:write, :exclusive]) do
      {:ok, io} ->
        File.close(io)
        write_owner(lock)

      {:error, :eexist} ->
        if stale?(lock), do: take_over(lock), else: {:error, {:audit_locked, File.read!(lock)}}
    end
  end

  @doc "Release the lock if this process holds it."
  @spec release(Path.t()) :: :ok
  def release(path) do
    lock = path <> ".lock"
    if File.exists?(lock) and File.read!(lock) == owner(), do: File.rm(lock)
    :ok
  end

  # ---- internals ---------------------------------------------------------

  defp owner, do: "#{:os.getpid()} #{:erlang.pid_to_list(self())}"

  defp write_owner(lock), do: File.write!(lock, owner())

  defp take_over(lock) do
    File.rm(lock)
    acquire(String.replace_suffix(lock, ".lock", ""))
  end

  defp stale?(lock) do
    case lock |> File.read!() |> String.split(" ", parts: 2) do
      [os_pid, erl_pid] -> not owner_alive?(os_pid, erl_pid)
      _unreadable -> true
    end
  end

  defp owner_alive?(os_pid, erl_pid) do
    if os_pid == List.to_string(:os.getpid()), do: local_alive?(erl_pid), else: os_alive?(os_pid)
  end

  defp local_alive?(erl_pid) do
    erl_pid |> String.to_charlist() |> :erlang.list_to_pid() |> Process.alive?()
  end

  defp os_alive?(os_pid) do
    match?({_, 0}, System.cmd("kill", ["-0", os_pid], stderr_to_stdout: true))
  end
end

defmodule ActorTxSpike.Metrics.VM do
  @moduledoc """
  Thin, side-effect-free readers over the Erlang VM's own instrumentation.
  Kept separate so `ActorTxSpike.Metrics` can stay about *when* and *how*, not *what*.
  """

  @doc "Turn on scheduler wall-time accounting (a cheap, VM-global flag)."
  def enable_scheduler_accounting, do: :erlang.system_flag(:scheduler_wall_time, true)

  @doc "Memory by category, in bytes."
  def memory do
    m = :erlang.memory()

    %{
      total: m[:total],
      processes: m[:processes],
      binary: m[:binary],
      ets: m[:ets],
      code: m[:code],
      atom: m[:atom],
      system: m[:system]
    }
  end

  @doc "Cumulative scheduler `{active, total}` wall time across all schedulers."
  def scheduler_wall_time do
    :scheduler_wall_time
    |> :erlang.statistics()
    |> Enum.reduce({0, 0}, fn {_id, active, total}, {a, t} -> {a + active, t + total} end)
  end

  @doc "Cumulative VM CPU time in ms (sum over schedulers) and total reductions."
  def cpu_ms, do: elem(:erlang.statistics(:runtime), 0)
  def reductions, do: elem(:erlang.statistics(:reductions), 0)

  @doc "Total run queue length."
  def run_queue, do: :erlang.statistics(:total_run_queue_lengths)

  @doc "Garbage collections performed so far."
  def gc_count, do: elem(:erlang.statistics(:garbage_collection), 0)

  def process_count, do: :erlang.system_info(:process_count)
  def port_count, do: :erlang.system_info(:port_count)

  @doc "Cumulative bytes received / sent over all TCP sockets owned by this VM."
  def tcp_bytes do
    for port <- :erlang.ports(),
        match?({:name, ~c"tcp_inet"}, :erlang.port_info(port, :name)),
        {:ok, stats} <- [:inet.getstat(port, [:recv_oct, :send_oct])],
        reduce: {0, 0, 0} do
      {recv, send, n} -> {recv + (stats[:recv_oct] || 0), send + (stats[:send_oct] || 0), n + 1}
    end
  end

  @doc "Top `n` processes by memory, with mailbox length and a human label."
  def top_processes(n) do
    Process.list()
    |> Enum.flat_map(fn pid ->
      case Process.info(pid, [
             :memory,
             :message_queue_len,
             :registered_name,
             :initial_call,
             :reductions
           ]) do
        nil ->
          []

        info ->
          [
            %{
              label: label(info),
              memory: info[:memory],
              mailbox: info[:message_queue_len],
              reductions: info[:reductions]
            }
          ]
      end
    end)
    |> Enum.sort_by(& &1.memory, :desc)
    |> Enum.take(n)
  end

  defp label(info) do
    case info[:registered_name] do
      name when is_atom(name) and name not in [nil, []] -> inspect(name)
      _ -> format_mfa(info[:initial_call])
    end
  end

  defp format_mfa({m, f, a}), do: "#{inspect(m)}.#{f}/#{a}"
end

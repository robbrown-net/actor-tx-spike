defmodule ActorTxSpike.Provider.Fake do
  @moduledoc """
  Scriptable fake provider for tests and experiments. Implements the
  `ActorTxSpike.Provider` behaviour; the adapter `config` is the fake's pid.

  Per-operation modes (`:quote_mode`, `:execute_mode`, `:status_mode`,
  `:probe_mode`): `:ok`, `:timeout`, `{:error, reason}`, plus `:reject`
  (quote/execute), `:in_doubt` (execute: the payment HAPPENS but the answer is
  lost) and `:unknown` (status). Also scriptable: `:rate`, `:fee`,
  `:latency_ms`, `:version`, `:quote_ttl_ms`.

  Execution is idempotent per key, like a well-behaved provider, and the fake
  records every execution so tests can assert "no double payment".
  """
  @behaviour ActorTxSpike.Provider

  use Agent

  alias ActorTxSpike.Provider.Quote

  @defaults %{
    id: :fake,
    rate: 1.0,
    fee: 0.0,
    latency_ms: 0,
    version: "1",
    quote_ttl_ms: 60_000,
    quote_mode: :ok,
    execute_mode: :ok,
    status_mode: :ok,
    probe_mode: :ok
  }

  def start_link(opts) do
    settings = Map.merge(@defaults, Map.new(opts))
    Agent.start_link(fn -> %{settings: settings, executed: %{}, execute_calls: 0} end)
  end

  @doc "Change settings on the fly (simulate decay, price moves, drift)."
  def configure(pid, changes) do
    Agent.update(pid, fn s -> %{s | settings: Map.merge(s.settings, Map.new(changes))} end)
  end

  @doc "Map of idempotency_key => receipt for payments that actually happened."
  def executions(pid), do: Agent.get(pid, & &1.executed)

  @doc "How many times execute/3 was invoked (including retries and failures)."
  def execute_calls(pid), do: Agent.get(pid, & &1.execute_calls)

  @impl true
  def request_quote(pid, request) do
    settings = settings(pid)
    delay(settings)

    case settings.quote_mode do
      :ok ->
        {:ok,
         %Quote{
           provider: settings.id,
           request_id: request.id,
           rate: settings.rate,
           fee: settings.fee,
           expires_at_ms: System.system_time(:millisecond) + settings.quote_ttl_ms
         }}

      :reject ->
        {:rejected, :no_liquidity}

      other ->
        failure(other)
    end
  end

  @impl true
  def execute(pid, %Quote{} = quote, key) do
    settings =
      Agent.get_and_update(pid, &{&1.settings, %{&1 | execute_calls: &1.execute_calls + 1}})

    delay(settings)

    cond do
      settings.execute_mode == :timeout or match?({:error, _}, settings.execute_mode) ->
        failure(settings.execute_mode)

      Quote.expired?(quote, System.system_time(:millisecond)) ->
        {:rejected, :quote_expired}

      settings.execute_mode == :reject ->
        {:rejected, :declined}

      settings.execute_mode == :in_doubt ->
        _ = perform(pid, settings, quote, key)
        :in_doubt

      true ->
        {:ok, perform(pid, settings, quote, key)}
    end
  end

  @impl true
  def status(pid, key) do
    settings = settings(pid)
    delay(settings)

    case settings.status_mode do
      :ok ->
        case Map.fetch(executions(pid), key) do
          {:ok, receipt} -> {:ok, receipt}
          :error -> :not_found
        end

      :unknown ->
        :unknown

      other ->
        failure(other)
    end
  end

  @impl true
  def probe(pid) do
    settings = settings(pid)
    delay(settings)

    case settings.probe_mode do
      :ok -> {:ok, %{version: settings.version, latency_ms: settings.latency_ms}}
      other -> failure(other)
    end
  end

  defp perform(pid, settings, quote, key) do
    Agent.get_and_update(pid, fn state ->
      case Map.fetch(state.executed, key) do
        {:ok, receipt} ->
          {receipt, state}

        :error ->
          receipt = %{provider: settings.id, key: key, rate: quote.rate, fee: quote.fee}
          {receipt, %{state | executed: Map.put(state.executed, key, receipt)}}
      end
    end)
  end

  defp settings(pid), do: Agent.get(pid, & &1.settings)
  defp delay(%{latency_ms: ms}) when ms > 0, do: Process.sleep(ms)
  defp delay(_), do: :ok
  defp failure(:timeout), do: :timeout
  defp failure({:error, _} = error), do: error
end

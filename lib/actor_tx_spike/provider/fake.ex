defmodule ActorTxSpike.Provider.Fake do
  @moduledoc """
  Scriptable fake provider for tests, experiments and the live demo.
  Implements the `ActorTxSpike.Provider` behaviour; the adapter `config` is the
  fake's pid.

  ## Scripting

  Each operation has a mode (`:quote_mode`, `:accept_quote_mode`,
  `:authorise_mode`, `:pay_mode`, `:settle_mode`, `:cancel_accept_mode`,
  `:void_authorisation_mode`, `:refund_mode`, `:status_mode`, `:probe_mode`):

  * `:ok`
  * `:timeout`           no answer; the effect did NOT happen
  * `{:error, reason}`   technical failure; the effect did NOT happen
  * `:reject`            business "no" (quote/effects/undos)
  * `:in_doubt`          effectful ops: the effect HAPPENS but the answer is lost
  * `:unknown`           status only

  A mode may also be a **list**: modes are consumed one per call and the last
  one sticks, e.g. `pay_mode: [:timeout, :ok]` = fail once, then succeed.

  Also scriptable: `:rate`, `:fee`, `:latency_ms`, `:version`, `:quote_ttl_ms`.

  ## Observability

  The fake narrates what a real service would: it announces receiving each
  request, how long it is "processing", and what it answers. Every effect is
  recorded so tests can assert exactly what happened (e.g. no double payment).
  """
  @behaviour ActorTxSpike.Provider

  use Agent

  alias ActorTxSpike.{Events, Money}
  alias ActorTxSpike.Provider.Quote

  @mode_keys %{
    request_quote: :quote_mode,
    accept_quote: :accept_quote_mode,
    authorise: :authorise_mode,
    pay: :pay_mode,
    settle: :settle_mode,
    cancel_accept: :cancel_accept_mode,
    void_authorisation: :void_authorisation_mode,
    refund: :refund_mode,
    status: :status_mode,
    probe: :probe_mode
  }

  @defaults %{
    id: :fake,
    rate: 100_000_000,
    fee: 0,
    latency_ms: 0,
    version: "1",
    quote_ttl_ms: 60_000,
    quote_mode: :ok,
    accept_quote_mode: :ok,
    authorise_mode: :ok,
    pay_mode: :ok,
    settle_mode: :ok,
    cancel_accept_mode: :ok,
    void_authorisation_mode: :ok,
    refund_mode: :ok,
    status_mode: :ok,
    probe_mode: :ok
  }

  def start_link(opts) do
    settings = Map.merge(@defaults, opts |> Map.new() |> normalise())
    Agent.start_link(fn -> %{settings: settings, effects: %{}, calls: %{}} end)
  end

  @doc "Change settings on the fly (simulate decay, price moves, drift)."
  def configure(pid, changes) do
    Agent.update(pid, fn s ->
      %{s | settings: Map.merge(s.settings, changes |> Map.new() |> normalise())}
    end)
  end

  # Rate is accepted as a decimal (1.0820) and fee in major units (1.50); both
  # are held exactly, as a scaled integer and in minor units.
  defp normalise(settings) do
    settings
    |> then(
      &if(Map.has_key?(&1, :rate), do: Map.update!(&1, :rate, fn r -> to_rate(r) end), else: &1)
    )
    |> then(
      &if(Map.has_key?(&1, :fee), do: Map.update!(&1, :fee, fn f -> Money.minor(f) end), else: &1)
    )
  end

  defp amount_text(nil), do: "an unspecified amount"
  defp amount_text(amount), do: Money.format(amount)

  defp to_rate(r) when is_float(r) or is_binary(r), do: Money.rate(r)
  defp to_rate(r) when is_integer(r) and r >= 1_000_000, do: r
  defp to_rate(r) when is_integer(r), do: Money.rate(r)

  def settings(pid), do: Agent.get(pid, & &1.settings)

  @doc "Map of `{op, key}` => receipt for every effect that actually happened."
  def effects(pid), do: Agent.get(pid, & &1.effects)

  @doc "How many effects of this op actually happened."
  def effect_count(pid, op), do: pid |> effects() |> Enum.count(fn {{o, _}, _} -> o == op end)

  @doc "How many times each op was invoked (including failed and retried calls)."
  def calls(pid), do: Agent.get(pid, & &1.calls)

  @impl true
  def request_quote(pid, request) do
    {mode, s} = begin_call(pid, :request_quote)

    announce(
      s,
      request.id,
      :received,
      "received a quote request for #{amount_text(request[:amount])}"
    )

    delay(s, request.id)

    result =
      case mode do
        :ok ->
          {:ok,
           %Quote{
             provider: s.id,
             request_id: request.id,
             rate: s.rate,
             fee: s.fee,
             expires_at_ms: System.system_time(:millisecond) + s.quote_ttl_ms
           }}

        :reject ->
          {:rejected, :no_liquidity}

        other ->
          failure(other)
      end

    answered(s, request.id, :request_quote, result)
  end

  @impl true
  def accept_quote(pid, quote, key), do: effect(pid, :accept_quote, quote, key)

  @impl true
  def authorise(pid, quote, key), do: effect(pid, :authorise, quote, key)

  @impl true
  def pay(pid, quote, key), do: effect(pid, :pay, quote, key)

  @impl true
  def settle(pid, key), do: effect(pid, :settle, nil, key)

  @impl true
  def cancel_accept(pid, key), do: effect(pid, :cancel_accept, nil, key)

  @impl true
  def void_authorisation(pid, key), do: effect(pid, :void_authorisation, nil, key)

  @impl true
  def refund(pid, key), do: effect(pid, :refund, nil, key)

  @impl true
  def status(pid, op, key) do
    {mode, s} = begin_call(pid, :status)
    announce(s, tx_of(key), :received, "received a status lookup for #{op} #{key}")
    delay(s, tx_of(key))

    result =
      case mode do
        :ok ->
          case Map.fetch(effects(pid), {op, key}) do
            {:ok, receipt} -> {:ok, receipt}
            :error -> :not_found
          end

        :unknown ->
          :unknown

        other ->
          failure(other)
      end

    answered(s, tx_of(key), :status, result)
  end

  @impl true
  def probe(pid) do
    {mode, s} = begin_call(pid, :probe)
    announce(s, nil, :received, "received a health probe")
    delay(s, nil)

    result =
      case mode do
        :ok -> {:ok, %{version: s.version, latency_ms: s.latency_ms}}
        other -> failure(other)
      end

    answered(s, nil, :probe, result)
  end

  defp effect(pid, op, quote, key) do
    {mode, s} = begin_call(pid, op)
    tx = tx_of(key)
    announce(s, tx, :received, "received #{op} (key #{key})")
    delay(s, tx)

    result =
      cond do
        mode == :timeout or match?({:error, _}, mode) ->
          failure(mode)

        quote != nil and Quote.expired?(quote, System.system_time(:millisecond)) ->
          {:rejected, :quote_expired}

        mode == :reject ->
          {:rejected, :declined}

        mode == :in_doubt ->
          _ = perform(pid, s, op, quote, key)
          :in_doubt

        true ->
          {:ok, perform(pid, s, op, quote, key)}
      end

    answered(s, tx, op, result)
  end

  defp perform(pid, settings, op, quote, key) do
    Agent.get_and_update(pid, fn state ->
      case Map.fetch(state.effects, {op, key}) do
        {:ok, receipt} ->
          {receipt, state}

        :error ->
          receipt = %{
            provider: settings.id,
            op: op,
            key: key,
            rate: quote && quote.rate,
            fee: quote && quote.fee
          }

          {receipt, %{state | effects: Map.put(state.effects, {op, key}, receipt)}}
      end
    end)
  end

  # Take this call's mode (consuming scripted lists) and count the call.
  defp begin_call(pid, op) do
    Agent.get_and_update(pid, fn state ->
      key = Map.fetch!(@mode_keys, op)
      {mode, settings} = pop_mode(state.settings, key)
      calls = Map.update(state.calls, op, 1, &(&1 + 1))
      {{mode, settings}, %{state | settings: settings, calls: calls}}
    end)
  end

  defp pop_mode(settings, key) do
    case Map.fetch!(settings, key) do
      [mode] -> {mode, settings}
      [mode | rest] -> {mode, Map.put(settings, key, rest)}
      mode -> {mode, settings}
    end
  end

  defp delay(%{latency_ms: ms} = s, tx) when ms > 0 do
    announce(s, tx, :processing, "processing (takes ~#{ms}ms)")
    Process.sleep(ms)
  end

  defp delay(_s, _tx), do: :ok

  defp answered(s, tx, op, result) do
    announce(s, tx, :responded, "#{op} -> #{describe(result)}", %{op: op, result: tag(result)})
    result
  end

  defp announce(s, tx, kind, message, data \\ %{}) do
    Events.emit(tx, {:provider, s.id}, kind, message, data)
  end

  defp describe({:ok, %Quote{} = q}),
    do: "quote rate #{Money.format_rate(q.rate)} fee #{Money.format(q.fee)}"

  defp describe({:ok, _receipt}), do: "ok"
  defp describe({:rejected, why}), do: "rejected (#{why})"
  defp describe({:error, why}), do: "error (#{inspect(why)})"
  defp describe(other) when is_atom(other), do: Atom.to_string(other)

  defp tag({:ok, _}), do: :ok
  defp tag({tag, _}), do: tag
  defp tag(other), do: other

  defp tx_of(key), do: key |> String.split(":") |> hd()

  defp failure(:timeout), do: :timeout
  defp failure({:error, _} = error), do: error
end

defmodule ActorTxSpike.Recovery do
  @moduledoc """
  Recover open transactions from the durable log after a restart.

  The audit log is a write-ahead log: before any provider call, `Payment` waits
  until the record of that call is on disk. So after a crash the log tells the
  truth about what *might* have been sent:

    * a call **in the log** may have reached the provider, with the answer lost:
      it is reconciled by asking the provider (`status`) and never repeated blindly;
    * a call **not in the log** was never sent: it is safe to make.

  `plan/1` is a pure function from one transaction's records to where it stood:

  | mode | meaning | resumes by |
  |---|---|---|
  | `:fresh` | no provider chosen yet; quoting has no side effects | quoting again |
  | `:next` | the last step succeeded (or a retry was pending); the next was never sent | sending the next step |
  | `:doubt` | a step was sent (or its status was being checked) with no answer recorded | asking the provider what it holds |
  | `:undo` | compensation was under way | finishing the remaining undos |
  | `:complete` | every step is done; only the completion was not recorded | recording it |
  | `:review` | the log cannot say; a person must decide | needs_review |

  The money rules of `Payment` then apply unchanged: an unresolved pay or settle
  is never failed over, an unconfirmed reversible step is cancelled by key.

  `recover/2` starts a resumed `Payment` for every open transaction in the given
  days (loaded logs). Estimated costs and timings of a resumed transaction cover
  only its second life.
  """

  alias ActorTxSpike.{Guarantee, TransactionSupervisor}
  alias ActorTxSpike.Provider.Quote
  alias ActorTxSpike.Replay.{Day, Tx}

  @next %{"accept_quote" => "authorise", "authorise" => "pay", "pay" => "settle", "settle" => nil}
  @terminal ~w(tx_completed tx_failed needs_review)
  @opt_keys ~w(deadline_ms step_timeout_ms max_attempts backoff_ms reconcile_attempts reconcile_interval_ms linger_ms)a

  @doc "Where one transaction stood, from its records in order. `nil` if it never started."
  @spec plan([map()]) :: nil | %{status: :done} | map()
  def plan(records) do
    received = Tx.first(records, "tx_received")

    cond do
      received == nil -> nil
      Enum.any?(records, &(&1["kind"] in @terminal)) -> %{status: :done, tx: received["tx"]}
      true -> open_plan(records, received)
    end
  end

  @doc "Plans for every open transaction in a day."
  @spec open(Day.t()) :: [map()]
  def open(%Day{} = day) do
    for id <- day.tx_order, p = plan(day.by_tx[id]), p.status == :open, do: p
  end

  @doc """
  Resume every open transaction found in `days` (one or several loaded logs).

  Options: `:providers` (the live registry, `[%{id:, server:}]`), `:eligibility`,
  `:ledger`, `:guarantees` is not needed (the guarantee is in the record), `:opts`
  (payment settings, overriding the recorded ones). A transaction present in
  several regional logs is resumed once. Returns `[{tx_id, :ok | {:error, reason}}]`.
  """
  @spec recover([Day.t()] | Day.t(), keyword()) :: [{String.t(), :ok | {:error, term()}}]
  def recover(days, opts) do
    days
    |> List.wrap()
    |> Enum.flat_map(&open/1)
    |> Enum.uniq_by(& &1.tx)
    |> Enum.map(&{&1.tx, start(&1, opts)})
  end

  # ---- planning ----------------------------------------------------------

  defp open_plan(records, received) do
    data = received["data"]
    chosen = Tx.all(records, "provider_chosen")
    deadline = Tx.first(records, "deadline_set")

    {:ok, policy} = Tx.policy(received, %{})

    base = %{
      status: :open,
      tx: received["tx"],
      request: data["request"],
      policy: policy,
      guarantee: guarantee(data["guarantee"]),
      config: config(data["config"]),
      started_ms: received["at"],
      region: deadline && deadline["data"]["region"],
      deadline_ms: deadline && deadline["data"]["deadline_ms"],
      eligibility_logs: data["eligibility"] && data["eligibility"]["logs"],
      quotes: quotes(records),
      latencies: latencies(records),
      nets: nets(records, data["request"]["amount"]),
      provider: nil,
      op: nil,
      done: [],
      remaining: [],
      undo_queue: [],
      after_undo: nil,
      reason: nil
    }

    case List.last(chosen) do
      nil -> Map.put(base, :mode, :fresh)
      last -> at_provider(base, records, last)
    end
  end

  defp at_provider(base, records, last_chosen) do
    provider = last_chosen["data"]["provider"]
    segment = records |> Enum.drop_while(&(&1 != last_chosen)) |> Enum.drop(1)

    done =
      for %{"kind" => k, "data" => %{"op" => op}} <- segment,
          k in ~w(step_succeeded reconciled),
          do: op

    base =
      Map.merge(base, %{
        provider: provider,
        done: done,
        remaining: Enum.map(last_chosen["data"]["remaining"] || [], &to_string/1)
      })

    cond do
      not Map.has_key?(base.quotes, provider) ->
        Map.merge(base, %{
          mode: :review,
          reason: "no quote from #{provider} was recorded, so its position cannot be rebuilt"
        })

      compensating?(segment) ->
        compensation(base, segment)

      true ->
        where_it_stood(base, segment)
    end
  end

  defp compensating?(segment),
    do:
      Enum.any?(
        segment,
        &(&1["kind"] in ~w(undo_started step_failed cancel_by_key deadline_missed))
      )

  defp compensation(base, segment) do
    doubtful = for %{"kind" => "cancel_by_key", "data" => %{"op" => op}} <- segment, do: op
    latest_first = Enum.reverse(base.done)
    undone = for %{"kind" => "undone", "data" => %{"op" => op}} <- segment, do: op

    queue =
      for original <- doubtful ++ latest_first,
          undo = Tx.undo_for(original),
          undo not in undone,
          do: {undo, original}

    aborting = Enum.find(segment, &(&1["kind"] == "deadline_missed"))

    after_undo =
      if aborting, do: {:abort, aborting["data"]["region"]}, else: :failover

    Map.merge(base, %{mode: :undo, undo_queue: queue, after_undo: after_undo})
  end

  # No compensation under way: the last thing that happened decides.
  defp where_it_stood(base, segment) do
    last =
      segment
      |> Enum.filter(
        &(&1["kind"] in ~w(step_started step_succeeded reconciled step_retry in_doubt status_check reconcile_pending))
      )
      |> List.last()

    case last do
      nil ->
        Map.merge(base, %{mode: :next, op: "accept_quote"})

      %{"kind" => k, "data" => %{"op" => op}} when k in ~w(step_succeeded reconciled) ->
        after_success(base, op)

      %{"kind" => "step_retry", "data" => %{"op" => op}} ->
        Map.merge(base, %{mode: :next, op: op})

      %{"data" => %{"op" => op}} ->
        Map.merge(base, %{mode: :doubt, op: op})
    end
  end

  defp after_success(base, op) do
    case @next[op] do
      nil -> Map.put(base, :mode, :complete)
      next -> Map.merge(base, %{mode: :next, op: next})
    end
  end

  # ---- inputs rebuilt from the journal -----------------------------------

  defp quotes(records) do
    for %{"kind" => "quote_observed", "data" => %{"outcome" => "quote"} = d} <- records,
        into: %{} do
      {d["provider"],
       %Quote{
         provider: d["provider"],
         request_id: d["request_id"],
         rate: d["rate"],
         fee: d["fee"],
         expires_at_ms: d["expires_at_ms"]
       }}
    end
  end

  defp latencies(records) do
    for %{"kind" => "quote_observed", "data" => d} <- records,
        into: %{},
        do: {d["provider"], d["latency_ms"]}
  end

  defp nets(records, amount) do
    records
    |> quotes()
    |> Map.values()
    |> Enum.map(&(ActorTxSpike.Money.convert(amount, &1.rate) - &1.fee))
  end

  defp guarantee(nil), do: nil

  defp guarantee(g),
    do: %Guarantee{merchant_id: g["merchant_id"], period: g["period"], rate: g["rate"]}

  defp config(nil), do: []

  defp config(map) do
    for key <- @opt_keys, value = map[Atom.to_string(key)], do: {key, value}
  end

  # ---- starting ----------------------------------------------------------

  defp start(plan, opts) do
    request = atomize(plan.request)

    args = [
      id: plan.tx,
      request: request,
      providers: Keyword.fetch!(opts, :providers),
      policy: plan.policy,
      guarantee: plan.guarantee,
      eligibility: Keyword.get(opts, :eligibility, ActorTxSpike.Eligibility.open()),
      opts: Keyword.merge(plan.config, Keyword.get(opts, :opts, [])),
      resume: plan
    ]

    args = if opts[:ledger], do: args ++ [ledger: opts[:ledger]], else: args

    # {:error, {:already_started, pid}} when it is in fact still running
    with {:ok, _pid} <- TransactionSupervisor.start_payment(args), do: :ok
  end

  # request keys come back from JSON as strings; Payment reads them as atoms
  defp atomize(map), do: Map.new(map, fn {k, v} -> {existing_atom(k), v} end)

  defp existing_atom(key) do
    String.to_existing_atom(key)
  rescue
    ArgumentError -> key
  end
end

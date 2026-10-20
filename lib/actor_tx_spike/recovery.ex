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
    base = base_plan(records, received)

    case records |> Tx.all("provider_chosen") |> List.last() do
      nil -> Map.put(base, :mode, :fresh)
      last -> at_provider(base, records, last)
    end
  end

  # What is known before any provider was chosen, plus empty slots for the rest.
  defp base_plan(records, received) do
    data = received["data"]
    {:ok, policy} = Tx.policy(received, %{})

    %{
      status: :open,
      tx: received["tx"],
      request: data["request"],
      policy: policy,
      guarantee: guarantee(data["guarantee"]),
      config: config(data["config"]),
      started_ms: received["at"],
      eligibility_logs: eligibility_logs(data["eligibility"]),
      quotes: Tx.quotes(records),
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
    |> Map.merge(deadline(Tx.first(records, "deadline_set")))
  end

  defp deadline(nil), do: %{region: nil, deadline_ms: nil}

  defp deadline(%{"data" => d}), do: %{region: d["region"], deadline_ms: d["deadline_ms"]}

  defp eligibility_logs(nil), do: nil
  defp eligibility_logs(eligibility), do: eligibility["logs"]

  defp at_provider(base, records, last_chosen) do
    provider = last_chosen["data"]["provider"]
    segment = records |> Enum.drop_while(&(&1 != last_chosen)) |> Enum.drop(1)

    base =
      Map.merge(base, %{
        provider: provider,
        done: succeeded_ops(segment),
        remaining: remaining(last_chosen)
      })

    route(base, segment, Map.has_key?(base.quotes, provider), compensating?(segment))
  end

  defp succeeded_ops(segment) do
    for %{"kind" => kind, "data" => %{"op" => op}} <- segment,
        kind in ~w(step_succeeded reconciled),
        do: op
  end

  defp remaining(%{"data" => %{"remaining" => ops}}) when is_list(ops),
    do: Enum.map(ops, &to_string/1)

  defp remaining(_), do: []

  # The log can only be trusted to rebuild a position if it holds the provider's quote.
  defp route(base, _segment, false, _compensating?) do
    reason = "no quote from #{base.provider} was recorded, so its position cannot be rebuilt"
    Map.merge(base, %{mode: :review, reason: reason})
  end

  defp route(base, segment, true, true), do: compensation(base, segment)
  defp route(base, segment, true, false), do: where_it_stood(base, segment)

  defp compensating?(segment),
    do:
      Enum.any?(
        segment,
        &(&1["kind"] in ~w(undo_started step_failed cancel_by_key deadline_missed))
      )

  defp compensation(base, segment) do
    Map.merge(base, %{
      mode: :undo,
      undo_queue: undo_queue(base.done, segment),
      after_undo: after_undo(segment)
    })
  end

  # Undo what is in doubt first, then the completed steps latest first, skipping what is undone.
  defp undo_queue(done, segment) do
    doubtful = for %{"kind" => "cancel_by_key", "data" => %{"op" => op}} <- segment, do: op
    undone = for %{"kind" => "undone", "data" => %{"op" => op}} <- segment, do: op

    for original <- doubtful ++ Enum.reverse(done),
        undo = Tx.undo_for(original),
        undo not in undone,
        do: {undo, original}
  end

  defp after_undo(segment) do
    case Enum.find(segment, &(&1["kind"] == "deadline_missed")) do
      nil -> :failover
      %{"data" => data} -> {:abort, data["region"]}
    end
  end

  # No compensation under way: the last thing that happened decides.
  defp where_it_stood(base, segment) do
    segment
    |> Enum.filter(
      &(&1["kind"] in ~w(step_started step_succeeded reconciled step_retry in_doubt status_check reconcile_pending))
    )
    |> List.last()
    |> stood_at(base)
  end

  defp stood_at(nil, base), do: Map.merge(base, %{mode: :next, op: "accept_quote"})

  defp stood_at(%{"kind" => k, "data" => %{"op" => op}}, base)
       when k in ~w(step_succeeded reconciled),
       do: after_success(base, op)

  defp stood_at(%{"kind" => "step_retry", "data" => %{"op" => op}}, base),
    do: Map.merge(base, %{mode: :next, op: op})

  defp stood_at(%{"data" => %{"op" => op}}, base), do: Map.merge(base, %{mode: :doubt, op: op})

  defp after_success(base, op) do
    case @next[op] do
      nil -> Map.put(base, :mode, :complete)
      next -> Map.merge(base, %{mode: :next, op: next})
    end
  end

  # ---- inputs rebuilt from the journal -----------------------------------

  defp latencies(records) do
    for %{"kind" => "quote_observed", "data" => d} <- records,
        into: %{},
        do: {d["provider"], d["latency_ms"]}
  end

  defp nets(records, amount) do
    records
    |> Tx.quotes()
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

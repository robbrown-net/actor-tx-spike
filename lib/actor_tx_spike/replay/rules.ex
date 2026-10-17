defmodule ActorTxSpike.Replay.Rules do
  @moduledoc """
  Did the day follow the rules? Every check works from the recorded log alone.

  Money safety:

    * `:single_pay`: no transaction paid more than once
    * `:completed_is_whole`: a completed transaction did accept, authorise, pay
      and settle, in that order, at one provider
    * `:failed_clean`: nothing a provider was left holding when we moved on
      from it or gave up was left un-undone
    * `:no_failover_after_doubt`: once pay or settle was in doubt, we never
      moved to another provider
    * `:idempotency_keys`: every call used the key `"<tx>:<provider>:<step>"`
      and retries reused it

  Decisions (recomputed with the same code the live system used):

    * `:selection_reproducible`: the recorded inputs, judged again under the
      recorded policy, give the recorded ranking
    * `:quote_valid_at_accept`: the quote had not expired when accept was asked
    * `:deadline_respected`: no step began after the cut-off
    * `:margin_arithmetic` and `:economics_consistent`: guarantee margin and the
      recorded price discovery agree with the recorded quotes

  Record hygiene: `:time_ordered` (time never runs backwards).

  Each violation is `%{rule, tx, n, detail}`; `n` is the audit record number, so
  the evidence can be pulled straight from the log.
  """

  alias ActorTxSpike.{Money, Replay.Day, Replay.Tx}

  @expected_steps ~w(accept_quote authorise pay settle)
  @short %{
    "accept_quote" => "accept",
    "authorise" => "authorise",
    "pay" => "pay",
    "settle" => "settle"
  }

  @type violation :: %{rule: atom(), tx: String.t() | nil, n: integer() | nil, detail: String.t()}

  @doc """
  Check a day. Options: `deadline_tolerance_ms` (default 50; event stamps can
  trail the decision they describe by a few milliseconds).
  """
  @spec verify(Day.t(), keyword()) :: map()
  def verify(%Day{} = day, opts \\ []) do
    tolerance = Keyword.get(opts, :deadline_tolerance_ms, 50)

    per_tx =
      for id <- day.tx_order, reduce: [] do
        acc -> acc ++ check_tx(id, day.by_tx[id], tolerance)
      end

    violations = time_ordered(day.records) ++ per_tx
    summaries = Enum.map(day.tx_order, &Tx.summarize(day.by_tx[&1]))

    %{
      records: length(day.records),
      transactions: length(day.tx_order),
      outcomes: Enum.frequencies_by(summaries, & &1.outcome),
      violations: violations,
      ok?: violations == []
    }
  end

  # ---- per transaction ---------------------------------------------------

  defp check_tx(id, rs, tolerance) do
    s = Tx.summarize(rs)

    List.flatten([
      single_pay(id, rs),
      completed_is_whole(id, rs, s),
      failed_clean(id, rs, s),
      no_failover_after_doubt(id, rs),
      idempotency_keys(id, rs),
      selection_reproducible(id, rs),
      quote_valid_at_accept(id, rs),
      deadline_respected(id, rs, tolerance),
      margin_arithmetic(id, rs, s),
      economics_consistent(id, rs, s)
    ])
  end

  defp single_pay(id, rs) do
    pays =
      for %{"kind" => k, "data" => %{"op" => "pay"}} = r <- rs,
          k in ~w(step_succeeded reconciled),
          do: r

    if length(pays) > 1,
      do: [v(:single_pay, id, List.last(pays), "pay succeeded #{length(pays)} times")],
      else: []
  end

  defp completed_is_whole(id, rs, %{outcome: :completed, provider: provider} = s) do
    got = Tx.succeeded_ops(rs, provider)

    if got == @expected_steps,
      do: [],
      else: [
        v(
          :completed_is_whole,
          id,
          Tx.first(rs, "tx_completed"),
          "ran #{inspect(got)} at #{provider}, expected #{inspect(@expected_steps)} (#{s.outcome})"
        )
      ]
  end

  defp completed_is_whole(_id, _rs, _s), do: []

  # Every provider we left (by failover) or ended with on a failure must have
  # had its reversible effects undone.
  defp failed_clean(id, rs, s) do
    chosen = for %{"kind" => "provider_chosen", "data" => d} <- rs, do: d["provider"]
    abandoned = abandoned(chosen, s)

    for provider <- abandoned, undone_missing(rs, provider) != [] do
      v(
        :failed_clean,
        id,
        last_of(rs, provider),
        "#{provider} left holding #{inspect(undone_missing(rs, provider))} without undo"
      )
    end
  end

  defp abandoned(chosen, %{outcome: :failed}), do: Enum.uniq(chosen)
  defp abandoned(chosen, %{outcome: _}), do: chosen |> Enum.uniq() |> Enum.drop(-1)

  defp undone_missing(rs, provider) do
    needed = for op <- Tx.succeeded_ops(rs, provider), Tx.undo_for(op), do: Tx.undo_for(op)

    done = for %{"kind" => "undone", "data" => d} <- rs, d["provider"] == provider, do: d["op"]
    needed -- done
  end

  defp last_of(rs, provider),
    do: rs |> Enum.filter(&(&1["data"]["provider"] == provider)) |> List.last()

  defp no_failover_after_doubt(id, rs) do
    case Enum.find_index(rs, &(&1["kind"] == "in_doubt" and &1["data"]["op"] in ~w(pay settle))) do
      nil ->
        []

      i ->
        for %{"kind" => k} = r <- Enum.drop(rs, i + 1), k in ~w(provider_chosen failover) do
          v(:no_failover_after_doubt, id, r, "#{k} after pay or settle was in doubt")
        end
    end
  end

  defp idempotency_keys(id, rs) do
    calls = for %{"kind" => "step_started", "data" => d} = r <- rs, do: {r, d}

    wrong =
      for {r, d} <- calls, d["key"] != "#{id}:#{d["provider"]}:#{@short[d["op"]]}" do
        v(
          :idempotency_keys,
          id,
          r,
          "key #{inspect(d["key"])} is not #{id}:#{d["provider"]}:#{@short[d["op"]]}"
        )
      end

    varying =
      calls
      |> Enum.group_by(fn {_r, d} -> {d["provider"], d["op"]} end)
      |> Enum.flat_map(fn {{provider, op}, group} ->
        keys = group |> Enum.map(fn {_r, d} -> d["key"] end) |> Enum.uniq()

        if length(keys) > 1,
          do: [
            v(
              :idempotency_keys,
              id,
              elem(hd(group), 0),
              "#{provider} #{op} used several keys: #{inspect(keys)}"
            )
          ],
          else: []
      end)

    wrong ++ varying
  end

  defp selection_reproducible(id, rs) do
    selection = Tx.first(rs, "selection_made")

    if selection do
      {:ok, %{ranked: ranked}} = Tx.reselect(rs)
      recorded = selection["data"]["ranking"] || []
      mine = Enum.map(ranked, &{to_string(&1.provider_id), &1.net})
      theirs = Enum.map(recorded, &{&1["provider"], &1["net"]})

      if mine == theirs,
        do: [],
        else: [
          v(
            :selection_reproducible,
            id,
            selection,
            "recorded ranking #{inspect(theirs)} but the recorded inputs give #{inspect(mine)}"
          )
        ]
    else
      []
    end
  end

  defp quote_valid_at_accept(id, rs) do
    for %{"kind" => "step_started", "data" => %{"op" => "accept_quote"} = d, "at" => at} = r <-
          rs,
        quote = Tx.quote_for(rs, d["provider"]),
        at >= quote.expires_at_ms do
      v(
        :quote_valid_at_accept,
        id,
        r,
        "accept asked of #{d["provider"]} #{at - quote.expires_at_ms}ms after its quote expired"
      )
    end
  end

  defp deadline_respected(id, rs, tolerance) do
    case Tx.first(rs, "deadline_set") do
      nil ->
        []

      %{"data" => %{"deadline_ms" => deadline}} ->
        for %{"kind" => "step_started", "at" => at} = r <- rs, at > deadline + tolerance do
          v(:deadline_respected, id, r, "step began #{at - deadline}ms after the cut-off")
        end
    end
  end

  defp margin_arithmetic(id, rs, %{provider: provider}) do
    received = Tx.first(rs, "tx_received")
    event = Enum.find(rs, &(&1["kind"] in ~w(guarantee_margin guarantee_exposure)))
    guarantee = received && received["data"]["guarantee"]

    if event && guarantee && provider do
      amount = received["data"]["request"]["amount"]
      guaranteed = Money.convert(amount, guarantee["rate"])
      achieved = Tx.net_of(rs, provider)

      if event["data"]["guaranteed_net"] == guaranteed and
           event["data"]["achieved_net"] == achieved and
           event["data"]["margin"] == achieved - guaranteed,
         do: [],
         else: [
           v(
             :margin_arithmetic,
             id,
             event,
             "recorded margin #{event["data"]["margin"]}, recomputed #{achieved - guaranteed}"
           )
         ]
    else
      []
    end
  end

  defp economics_consistent(id, rs, %{outcome: :completed, provider: provider}) do
    case Tx.first(rs, "tx_economics") do
      %{"data" => %{"discovery" => %{"chosen_net" => chosen}}} = e ->
        net = Tx.net_of(rs, provider)

        if chosen == net,
          do: [],
          else: [
            v(
              :economics_consistent,
              id,
              e,
              "recorded chosen net #{chosen}, quote gives #{inspect(net)}"
            )
          ]

      _ ->
        []
    end
  end

  defp economics_consistent(_id, _rs, _s), do: []

  # ---- whole log ---------------------------------------------------------

  defp time_ordered(records) do
    records
    |> Enum.chunk_every(2, 1, :discard)
    |> Enum.filter(fn [a, b] -> b["at"] < a["at"] end)
    |> Enum.map(fn [_a, b] ->
      v(:time_ordered, b["tx"], b, "time ran backwards at record #{b["n"]}")
    end)
  end

  defp v(rule, tx, record, detail),
    do: %{rule: rule, tx: tx, n: record && record["n"], detail: detail}
end

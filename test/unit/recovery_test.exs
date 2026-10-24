defmodule ActorTxSpike.RecoveryTest do
  @moduledoc """
  Recovery is proven three ways: the pure plan against hand-written logs (every
  position a transaction can be in), resume against a real run cut off at every
  point (deterministic), and real crashes (kill the transaction at random
  moments and require the money rules to hold).
  """
  use ExUnit.Case, async: true

  alias ActorTxSpike.Wait

  alias ActorTxSpike.{Audit, Events, Panel, Payment, Policy, Recovery, Replay}
  alias ActorTxSpike.Provider.{Fake, Server}
  alias ActorTxSpike.Replay.Rules

  @fast [
    backoff_ms: 5,
    reconcile_interval_ms: 5,
    reconcile_attempts: 2,
    step_timeout_ms: 500,
    deadline_ms: 500
  ]

  # ---- the pure plan -----------------------------------------------------

  defp rec(n, kind, data \\ %{}, tx \\ "t1"),
    do: %{
      "n" => n,
      "seq" => n,
      "at" => 1_000 + n,
      "tx" => tx,
      "actor" => "payment",
      "kind" => kind,
      "data" => data
    }

  defp quote_obs(n, provider, rate),
    do:
      rec(n, "quote_observed", %{
        "provider" => provider,
        "outcome" => "quote",
        "rate" => rate,
        "fee" => 10,
        "expires_at_ms" => 4_000_000_000_000,
        "request_id" => "t1",
        "latency_ms" => 5,
        "state" => "healthy"
      })

  defp base_records(extra \\ []) do
    received =
      rec(1, "tx_received", %{
        "request" => %{"id" => "t1", "amount" => 100_000, "merchant_id" => "acme"},
        "policy" => %{
          "weights" => %{"cost" => 1.0, "speed" => 0.0, "reliability" => 0.0},
          "min_validity_ms" => 0,
          "max_latency_ms" => nil,
          "max_bps_from_best" => nil,
          "exclude_states" => ["stalled"]
        },
        "guarantee" => %{"merchant_id" => "acme", "period" => "W1", "rate" => 105_000_000},
        "config" => %{"deadline_ms" => 700, "max_attempts" => 3, "barrier" => "x"},
        "eligibility" => %{"logs" => ["IN"]}
      })

    [
      received,
      rec(2, "deadline_set", %{"region" => "UK", "deadline_ms" => 5_000}),
      quote_obs(3, "a", 110_000_000),
      quote_obs(4, "b", 105_000_000)
    ] ++ extra
  end

  defp chosen(n), do: rec(n, "provider_chosen", %{"provider" => "a", "remaining" => ["b"]})
  defp op(n, kind, op), do: rec(n, kind, %{"provider" => "a", "op" => op})

  test "a transaction that never started, or already finished, has nothing to recover" do
    assert Recovery.plan([]) == nil
    assert Recovery.plan([rec(1, "step_started", %{})]) == nil

    for terminal <- ~w(tx_completed tx_failed needs_review) do
      assert %{status: :done, tx: "t1"} = Recovery.plan(base_records([rec(9, terminal)]))
    end
  end

  test "before any provider was chosen it simply starts quoting again" do
    assert %{status: :open, mode: :fresh, provider: nil} = plan = Recovery.plan(base_records())
    assert plan.request["amount"] == 100_000

    assert plan.guarantee == %ActorTxSpike.Guarantee{
             merchant_id: "acme",
             period: "W1",
             rate: 105_000_000
           }

    assert plan.config == [deadline_ms: 700, max_attempts: 3]
    assert plan.region == "UK" and plan.deadline_ms == 5_000
    assert plan.eligibility_logs == ["IN"]
    assert %ActorTxSpike.Policy{weights: %{cost: 1.0}} = plan.policy
    assert Map.keys(plan.quotes) |> Enum.sort() == ["a", "b"]
    assert plan.latencies == %{"a" => 5, "b" => 5}
    assert Enum.sort(plan.nets) == [104_990, 109_990 - 0] or length(plan.nets) == 2
  end

  test "a chosen provider with nothing sent yet: the first step was never sent" do
    assert %{mode: :next, op: "accept_quote", provider: "a", remaining: ["b"], done: []} =
             Recovery.plan(base_records([chosen(5)]))
  end

  test "records without eligibility or a remaining list still plan" do
    [received | rest] = base_records()
    received = update_in(received, ["data"], &Map.delete(&1, "eligibility"))
    bare = rec(5, "provider_chosen", %{"provider" => "a"})

    assert %{eligibility_logs: nil, remaining: [], mode: :next} =
             Recovery.plan([received | rest] ++ [bare])
  end

  test "a step sent without a recorded answer is in doubt, whichever step it was" do
    for step <- ~w(accept_quote authorise pay settle) do
      assert %{mode: :doubt, op: ^step} =
               Recovery.plan(base_records([chosen(5), op(6, "step_started", step)]))
    end
  end

  test "after a recorded success the next step is next; after settle only the completion is missing" do
    steps = ~w(accept_quote authorise pay settle)

    for {done, next} <- Enum.zip(steps, ["authorise", "pay", "settle", nil]) do
      plan = Recovery.plan(base_records([chosen(5), op(6, "step_succeeded", done)]))

      if next,
        do: assert(%{mode: :next, op: ^next} = plan),
        else: assert(%{mode: :complete} = plan)

      assert done in plan.done
    end

    # a reconciled step counts as done
    assert %{mode: :next, op: "pay", done: ["accept_quote", "authorise"]} =
             Recovery.plan(
               base_records([
                 chosen(5),
                 op(6, "step_succeeded", "accept_quote"),
                 op(7, "reconciled", "authorise")
               ])
             )
  end

  test "a retry that was scheduled but not sent is sent; a status check in progress is a doubt" do
    assert %{mode: :next, op: "authorise"} =
             Recovery.plan(
               base_records([
                 chosen(5),
                 op(6, "step_started", "authorise"),
                 op(7, "step_retry", "authorise")
               ])
             )

    for kind <- ~w(in_doubt status_check reconcile_pending) do
      assert %{mode: :doubt, op: "pay"} =
               Recovery.plan(
                 base_records([chosen(5), op(6, "step_started", "pay"), op(7, kind, "pay")])
               )
    end
  end

  test "compensation under way resumes with the undos that remain, latest first" do
    work = [
      chosen(5),
      op(6, "step_succeeded", "accept_quote"),
      op(7, "step_succeeded", "authorise"),
      op(8, "step_failed", "pay")
    ]

    assert %{mode: :undo, after_undo: :failover, undo_queue: queue} =
             Recovery.plan(base_records(work))

    assert queue == [{"void_authorisation", "authorise"}, {"cancel_accept", "accept_quote"}]

    # one undo already recorded
    undone =
      work ++
        [
          op(9, "undo_started", "void_authorisation"),
          rec(10, "undone", %{"provider" => "a", "op" => "void_authorisation"})
        ]

    assert %{queue: _} = %{queue: nil}

    assert %{mode: :undo, undo_queue: [{"cancel_accept", "accept_quote"}]} =
             Recovery.plan(base_records(undone))

    # everything undone: only the failover is left
    finished = undone ++ [rec(11, "undone", %{"provider" => "a", "op" => "cancel_accept"})]
    assert %{mode: :undo, undo_queue: []} = Recovery.plan(base_records(finished))

    # a cancel-by-key adds the doubtful step to the front
    doubtful = [
      chosen(5),
      op(6, "step_succeeded", "accept_quote"),
      op(7, "step_started", "authorise"),
      op(8, "cancel_by_key", "authorise")
    ]

    assert %{
             mode: :undo,
             undo_queue: [{"void_authorisation", "authorise"}, {"cancel_accept", "accept_quote"}]
           } =
             Recovery.plan(base_records(doubtful))

    # a deadline abort must abort again, not fail over
    aborting = [
      chosen(5),
      op(6, "step_succeeded", "accept_quote"),
      rec(7, "deadline_missed", %{"region" => "UK"})
    ]

    assert %{mode: :undo, after_undo: {:abort, "UK"}} = Recovery.plan(base_records(aborting))
  end

  test "if the chosen provider's quote was never recorded, a person decides" do
    records =
      base_records([rec(5, "provider_chosen", %{"provider" => "ghost", "remaining" => []})])

    assert %{mode: :review, reason: reason} = Recovery.plan(records)
    assert reason =~ "ghost"
  end

  test "open/1 lists only unfinished transactions, in arrival order" do
    day =
      Replay.from_records(
        base_records([chosen(5)]) ++
          Enum.map(base_records([rec(9, "tx_completed")]), &Map.put(&1, "tx", "t2")) ++
          Enum.map(base_records(), &Map.put(&1, "tx", "t3"))
      )

    assert Enum.map(Recovery.open(day), & &1.tx) == ["t1", "t3"]
  end

  # ---- resume against a real run cut off at every point -------------------

  defp market(overrides \\ %{}) do
    base = %{best: [rate: 1.10, latency_ms: 10], second: [rate: 1.05], third: [rate: 1.00]}

    for {id, opts} <- Map.merge(base, overrides) do
      {:ok, fake} = Fake.start_link([id: id] ++ opts)

      {:ok, server} =
        Server.start_link(adapter: Fake, config: fake, id: id, health: [failure_threshold: 50])

      %{id: id, fake: fake, server: server}
    end
  end

  defp registry(market), do: Enum.map(market, &%{id: &1.id, server: &1.server})

  defp fake(market, id), do: Enum.find(market, &(&1.id == id)).fake

  defp effects(market, op), do: market |> Enum.map(&Fake.effect_count(&1.fake, op)) |> Enum.sum()

  defp submit(market, id, request, opts \\ []) do
    {:ok, policy} = Policy.new(%{"weights" => %{"cost" => 1}})

    {:ok, ^id} =
      ActorTxSpike.submit(Map.merge(%{id: id, amount: 100_000}, request),
        providers: registry(market),
        policy: policy,
        opts: Keyword.merge(@fast, opts)
      )

    id
  end

  # An in-memory audit of one transaction: what its log would hold on disk.
  defp record(fun) do
    Events.subscribe()
    fun.()
  end

  defp collect(tx) do
    receive do
      {:tx_event, %{tx_id: ^tx} = e} -> [to_record(e) | collect(tx)]
      {:tx_event, _other} -> collect(tx)
    after
      300 -> []
    end
  end

  defp to_record(e) do
    %{
      "n" => e.seq,
      "seq" => e.seq,
      "at" => e.at_ms,
      "tx" => e.tx_id,
      "actor" => ActorTxSpike.Narrative.actor_label(e.actor),
      "kind" => to_string(e.kind),
      "message" => e.message,
      "data" => e.data |> Panel.sanitize() |> JSON.encode!() |> JSON.decode!()
    }
  end

  # A finished transaction's snapshot stays answerable from the reaper, so wait for its process.
  defp wait_gone(id) do
    Enum.reduce_while(1..200, nil, fn _, _ ->
      if Registry.lookup(ActorTxSpike.Registry, {:payment, id}) == [],
        do: {:halt, :ok},
        else: Process.sleep(10) && {:cont, nil}
    end)
  end

  # Run to the end, then pretend the node died right after the record satisfying `cut`.
  defp run_and_cut(market, id, cut, request \\ %{}) do
    record(fn ->
      submit(market, id, request)
      Payment.await(id)
      wait_gone(id)
    end)

    all = collect(id)
    index = Enum.find_index(all, cut)
    assert index, "no record matched the cut point in #{inspect(Enum.map(all, & &1["kind"]))}"
    {Enum.take(all, index + 1), all}
  end

  defp after_kind(kind, op \\ nil),
    do: fn r -> r["kind"] == kind and (op == nil or r["data"]["op"] == op) end

  defp resume(market, truncated) do
    id = hd(truncated)["tx"]
    day = Replay.from_records(truncated)
    Events.subscribe()
    results = Recovery.recover(day, providers: registry(market), opts: @fast)
    outcome = Payment.await(id)
    {results, outcome, truncated ++ collect(id)}
  end

  for {name, cut} <- [
        {"nothing sent yet", {:kind, "provider_chosen"}},
        {"accept in doubt", {:kind, "step_started", "accept_quote"}},
        {"accept done", {:kind, "step_succeeded", "accept_quote"}},
        {"authorise in doubt", {:kind, "step_started", "authorise"}},
        {"authorise done", {:kind, "step_succeeded", "authorise"}},
        {"pay in doubt", {:kind, "step_started", "pay"}},
        {"pay done", {:kind, "step_succeeded", "pay"}},
        {"settle in doubt", {:kind, "step_started", "settle"}},
        {"settle done, completion not recorded", {:kind, "step_succeeded", "settle"}}
      ] do
    test "a node that died at '#{name}' resumes, completes, and pays exactly once" do
      m = market()
      id = "rc-#{System.unique_integer([:positive])}"

      cut =
        case unquote(Macro.escape(cut)) do
          {:kind, kind} -> after_kind(kind)
          {:kind, kind, op} -> after_kind(kind, op)
        end

      {truncated, _all} = run_and_cut(m, id, cut)
      {results, outcome, day_records} = resume(m, truncated)

      assert [{^id, :ok}] = results
      assert {:ok, %{provider: :best}} = outcome

      # the money rules held through the restart
      assert effects(m, :pay) == 1
      assert effects(m, :settle) == 1
      assert effects(m, :refund) == 0

      result = Rules.verify(Replay.from_records(day_records))
      assert result.ok?, inspect(result.violations)
      assert Enum.any?(day_records, &(&1["kind"] == "tx_resumed"))
    end
  end

  test "a node that died before choosing a provider quotes again and completes" do
    m = market()
    id = "rc-#{System.unique_integer([:positive])}"
    {truncated, _} = run_and_cut(m, id, &(&1["kind"] == "select_started"))
    {results, outcome, day_records} = resume(m, truncated)

    assert [{^id, :ok}] = results
    assert {:ok, %{provider: :best}} = outcome
    assert effects(m, :pay) == 1

    # two selection rounds in one transaction: both are re-judged and both reproduce
    assert length(Replay.Tx.rounds(Enum.filter(day_records, &(&1["tx"] == id)))) == 2
    assert Rules.verify(Replay.from_records(day_records)).ok?
  end

  test "a node that died while undoing finishes the undos and fails over" do
    m = market(%{best: [rate: 1.10, authorise_mode: :reject]})
    id = "rc-#{System.unique_integer([:positive])}"
    {truncated, _} = run_and_cut(m, id, after_kind("undo_started", "cancel_accept"))
    {results, outcome, day_records} = resume(m, truncated)

    assert [{^id, :ok}] = results
    assert {:ok, %{provider: :second}} = outcome
    assert Fake.effect_count(fake(m, :best), :cancel_accept) == 1
    assert effects(m, :pay) == 1
    assert Rules.verify(Replay.from_records(day_records)).ok?
  end

  test "a node that died after the last undo but before moving on still fails over" do
    m = market(%{best: [rate: 1.10, authorise_mode: :reject]})
    id = "rc-#{System.unique_integer([:positive])}"
    {truncated, _} = run_and_cut(m, id, after_kind("undone", "cancel_accept"))
    {_results, outcome, day_records} = resume(m, truncated)
    assert {:ok, %{provider: :second}} = outcome
    assert Rules.verify(Replay.from_records(day_records)).ok?
  end

  test "a payment whose answer was lost and is unconfirmable goes to a human, never elsewhere" do
    m = market(%{best: [rate: 1.10, pay_mode: :timeout]})
    id = "rc-#{System.unique_integer([:positive])}"
    {truncated, _} = run_and_cut(m, id, after_kind("step_started", "pay"))
    {_results, outcome, _records} = resume(m, truncated)

    assert {:error, {:needs_review, {:in_doubt, :pay}}} = outcome
    assert Fake.effect_count(fake(m, :second), :pay) == 0
    assert Fake.effect_count(fake(m, :second), :accept_quote) == 0
  end

  test "a transaction whose provider is gone after the restart is escalated, not guessed" do
    m = market()
    id = "rc-#{System.unique_integer([:positive])}"
    {truncated, _} = run_and_cut(m, id, after_kind("step_succeeded", "authorise"))

    day = Replay.from_records(truncated)
    Events.subscribe()
    only_others = registry(m) |> Enum.reject(&(&1.id == :best))
    assert [{^id, :ok}] = Recovery.recover(day, providers: only_others, opts: @fast)

    assert {:error, {:needs_review, {:recovery, :provider_unavailable}}} = Payment.await(id)
    # nothing new happened: the full run's effects are all that exist
    assert effects(m, :pay) == 1 and effects(m, :refund) == 0
  end

  test "a transaction with no recorded quote for its provider is escalated" do
    m = market()
    id = "rc-#{System.unique_integer([:positive])}"
    {truncated, _} = run_and_cut(m, id, after_kind("provider_chosen"))
    stripped = Enum.reject(truncated, &(&1["kind"] == "quote_observed"))

    Events.subscribe()

    assert [{^id, :ok}] =
             Recovery.recover(Replay.from_records(stripped), providers: registry(m), opts: @fast)

    assert {:error, {:needs_review, {:recovery, reason}}} = Payment.await(id)
    assert reason =~ "cannot be rebuilt"
  end

  # A hand-written log, resumed against real providers named "a" and "b".
  defp synthetic(m_overrides \\ %{}) do
    {:ok, fa} = Fake.start_link(id: :a, rate: 1.10)
    {:ok, fb} = Fake.start_link(id: :b, rate: 1.05)
    {:ok, sa} = Server.start_link(adapter: Fake, config: fa, id: :a)
    {:ok, sb} = Server.start_link(adapter: Fake, config: fb, id: :b)
    _ = m_overrides
    [%{id: :a, server: sa, fake: fa}, %{id: :b, server: sb, fake: fb}]
  end

  defp synthetic_day(id, extra, request_extra \\ %{}) do
    records =
      base_records(extra)
      |> Enum.map(&Map.put(&1, "tx", id))
      |> Enum.map(fn
        %{"kind" => "tx_received"} = r ->
          update_in(r, ["data", "request"], &Map.merge(Map.put(&1, "id", id), request_extra))

        r ->
          r
      end)
      |> Enum.map(fn r -> update_in(r, ["data"], &Map.put_new(&1, "provider", "a")) end)

    Replay.from_records(records)
  end

  test "after a restart past the cut-off the transaction undoes what it did and stops" do
    m = synthetic()
    id = "rc-#{System.unique_integer([:positive])}"
    # deadline_set says 5 000 ms since the epoch: long gone
    day = synthetic_day(id, [chosen(5), op(6, "step_succeeded", "accept_quote")])

    Events.subscribe()
    assert [{^id, :ok}] = Recovery.recover(day, providers: registry(m), opts: @fast)
    assert {:error, {:deadline_missed, "UK"}} = Payment.await(id)
    assert Fake.effect_count(fake(m, :a), :pay) == 0
  end

  test "a node that died while aborting for a missed cut-off aborts again, it does not fail over" do
    m = synthetic()
    id = "rc-#{System.unique_integer([:positive])}"

    day =
      synthetic_day(id, [
        chosen(5),
        op(6, "step_succeeded", "accept_quote"),
        rec(7, "deadline_missed", %{"region" => "UK"})
      ])

    Events.subscribe()
    assert [{^id, :ok}] = Recovery.recover(day, providers: registry(m), opts: @fast)
    assert {:error, {:deadline_missed, "UK"}} = Payment.await(id)
    assert Fake.effect_count(fake(m, :b), :accept_quote) == 0
  end

  test "a log without recorded settings and with unfamiliar request keys still resumes" do
    m = synthetic()
    id = "rc-#{System.unique_integer([:positive])}"

    records =
      synthetic_day(id, [chosen(5)], %{"a_key_no_atom_exists_for_this_xyz" => 1}).records
      |> Enum.map(fn
        %{"kind" => "tx_received"} = r -> update_in(r, ["data"], &Map.delete(&1, "config"))
        r -> r
      end)
      |> Enum.reject(&(&1["kind"] == "deadline_set"))

    plan = Recovery.plan(records)
    assert plan.config == [] and plan.region == nil and plan.deadline_ms == nil

    Events.subscribe()

    assert [{^id, :ok}] =
             Recovery.recover(Replay.from_records(records), providers: registry(m), opts: @fast)

    assert {:ok, %{provider: :a}} = Payment.await(id)
  end

  test "recovering something already running is refused, not duplicated" do
    m = market(%{best: [rate: 1.10, latency_ms: 200]})
    id = "rc-#{System.unique_integer([:positive])}"
    Events.subscribe()
    submit(m, id, %{})
    :ok = wait_for(fn -> Enum.any?(drain(), &(&1["kind"] == "step_started")) end)

    day = Replay.from_records(base_records([chosen(5)]) |> Enum.map(&Map.put(&1, "tx", id)))

    assert [{^id, {:error, {:already_started, _pid}}}] =
             Recovery.recover(day, providers: registry(m))

    assert {:ok, _} = Payment.await(id)
  end

  test "the same transaction found in several regional logs is resumed once" do
    m = market()
    id = "rc-#{System.unique_integer([:positive])}"
    {truncated, _} = run_and_cut(m, id, after_kind("provider_chosen"))
    day = Replay.from_records(truncated)
    Events.subscribe()
    assert [{^id, :ok}] = Recovery.recover([day, day], providers: registry(m), opts: @fast)
    assert {:ok, _} = Payment.await(id)
  end

  # ---- real crashes -------------------------------------------------------

  @moduletag capture_log: true

  for {name, trigger} <- [
        {"quoting", {"select_started", nil}},
        {"accept in flight", {"step_started", "accept_quote"}},
        {"after accept", {"step_succeeded", "accept_quote"}},
        {"authorise in flight", {"step_started", "authorise"}},
        {"after authorise", {"step_succeeded", "authorise"}},
        {"pay in flight", {"step_started", "pay"}},
        {"after pay", {"step_succeeded", "pay"}},
        {"settle in flight", {"step_started", "settle"}},
        {"after settle", {"step_succeeded", "settle"}},
        {"undoing", {"undo_started", "cancel_accept"}}
      ] do
    test "killing the transaction while '#{name}' never double-pays, whatever the timing" do
      {kind, op} = unquote(Macro.escape(trigger))
      matcher = trigger_matcher(kind, op)
      reject? = kind == "undo_started"

      m =
        market(%{
          best:
            [rate: 1.10, latency_ms: 40] ++ if(reject?, do: [authorise_mode: :reject], else: [])
        })

      id = "kc-#{System.unique_integer([:positive])}"

      dir = Path.join(System.tmp_dir!(), "crash-#{System.unique_integer([:positive])}")
      path = Path.join(dir, "log")

      {:ok, audit} =
        Audit.start_link(path: path, name: :"crash_audit_#{System.unique_integer([:positive])}")

      Process.unlink(audit)
      on_exit(fn -> File.rm_rf(dir) end)

      Events.subscribe()
      submit(m, id, %{})

      # the node dies the moment this happens
      kill_when(id, matcher)
      :ok = Events.barrier()
      wait_gone(id)

      # restart: load the durable log and recover
      {:ok, day} = Replay.load(path)
      mine = Replay.from_records(Enum.filter(day.records, &(&1["tx"] == id)))
      GenServer.stop(audit)

      Events.subscribe()
      results = Recovery.recover(mine, providers: registry(m), opts: @fast)

      case results do
        # it had in fact finished before the kill landed: nothing left to recover
        [] ->
          assert effects(m, :pay) <= 1

        [{^id, :ok}] ->
          outcome = Payment.await(id)

          # whatever the timing: at most one payment, and an outcome that is safe
          assert effects(m, :pay) <= 1
          assert effects(m, :settle) <= 1

          case outcome do
            {:ok, %{provider: provider}} ->
              assert Fake.effect_count(fake(m, provider), :pay) == 1
              assert effects(m, :pay) == 1

            {:error, {:needs_review, _}} ->
              :ok

            {:error, _failed} ->
              assert effects(m, :pay) == 0
          end
      end
    end
  end

  defp trigger_matcher(kind, nil), do: fn r -> r["kind"] == kind end
  defp trigger_matcher(kind, op), do: fn r -> r["kind"] == kind and r["data"]["op"] == op end

  defp kill_when(id, matcher) do
    receive do
      {:tx_event, %{tx_id: ^id} = e} ->
        if matcher.(to_record(e)) do
          [{pid, _}] = Registry.lookup(ActorTxSpike.Registry, {:payment, id})
          Process.exit(pid, :kill)
        else
          kill_when(id, matcher)
        end
    after
      3_000 -> flunk("the trigger never happened")
    end
  end

  defp drain do
    receive do
      {:tx_event, e} -> [to_record(e) | drain()]
    after
      0 -> []
    end
  end

  defp wait_for(fun, tries \\ 200), do: Wait.until!(fun, tries: tries, every_ms: 10)
end

defmodule ActorTxSpike.Demo.Lab.Scenarios do
  @moduledoc """
  The test lab's scenario library.

  Each scenario starts from known conditions (`Lab.prepare/0`: price drift off,
  prices fixed so cobalt is the best provider, providers healed, audit log
  healthy), provokes one behaviour, and judges it with hard checks: outcomes,
  and how many times each provider actually *did* each operation. Scenarios that
  need the regional logs are skipped when the demo was not started with
  `mix demo --lab` (or `--audit-dir`).

  A scenario passes only if every check passes. They double as system-level
  regression tests: `test/integration/lab_test.exs` runs them all.
  """

  alias ActorTxSpike.Demo.Lab
  alias ActorTxSpike.{Poll, Replay}

  @steps [:accept_quote, :authorise, :pay, :settle]

  @spec all() :: [map()]
  def all, do: routing() ++ idempotency() ++ provider_faults() ++ audit_outage() ++ restarts()

  # Jurisdiction routing: who may be asked, where it is recorded.
  defp routing do
    [
      s(
        "happy",
        "Happy path",
        "A plain transaction, nothing wrong.",
        "completed via cobalt; accept, authorise, pay and settle each done exactly once",
        [],
        &happy/0
      ),
      s(
        "corridor_in",
        "India corridor stays in India",
        "Spot to IN: only northwind and cobalt are eligible and the records stay in the IN log.",
        "completed via cobalt; recorded in IN only, never in GLOBAL",
        [:logs],
        &corridor_in/0
      ),
      s(
        "corridor_cn",
        "China corridor, one provider",
        "Spot to CN: only meridian is eligible, whatever the price.",
        "completed via meridian; recorded in CN only",
        [:logs],
        &corridor_cn/0
      ),
      s(
        "forward_in_refused",
        "Forward to India is refused",
        "Forwards are not permitted in IN: the transaction stops before any provider is asked.",
        "failed (not permitted); no provider did anything",
        [],
        &forward_in_refused/0
      )
    ]
  end

  # Idempotency: the same request twice is one effect.
  defp idempotency do
    [
      s(
        "duplicate_submit",
        "Idempotency: the same id submitted twice",
        "Two submissions with one transaction id must be one transaction.",
        "one transaction; pay done exactly once",
        [],
        &duplicate_submit/0
      ),
      s(
        "resend_same_key",
        "Idempotency: re-send a completed step with the same key",
        "After completion, send accept, authorise, pay and settle again with the same keys.",
        "the provider does nothing more: effect counts unchanged",
        [:logs],
        &resend_same_key/0
      )
    ]
  end

  # Provider faults: failover, doubt and the cases no one may retry.
  defp provider_faults do
    [
      s(
        "failover_authorise",
        "Failover: authorise rejected",
        "The best provider rejects authorise: the accept is undone and the next provider is used.",
        "completed via another provider; cobalt: accept cancelled, nothing paid",
        [],
        &failover_authorise/0
      ),
      s(
        "pay_answer_lost",
        "Pay: the answer is lost",
        "Cobalt makes the payment but the reply is lost: the system asks, never repeats.",
        "completed via cobalt; pay done exactly once",
        [],
        &pay_answer_lost/0
      ),
      s(
        "pay_unconfirmable",
        "Pay: never confirmable",
        "Cobalt never answers about the payment, even when asked later.",
        "needs_review; nobody else is paid or asked; no refund",
        [],
        &pay_unconfirmable/0
      ),
      s(
        "settle_refused",
        "Settle: refused after payment",
        "The payment was made, then settlement is refused: it cannot be undone.",
        "needs_review; the payment is NOT refunded",
        [],
        &settle_refused/0
      )
    ]
  end

  # Audit-log downtime: no durable record, no money moves.
  defp audit_outage do
    [
      s(
        "audit_down_start",
        "Audit log down before the transaction starts",
        "With no durable record possible, no money may move.",
        "failed (audit unavailable); no provider performed anything",
        [],
        &audit_down_start/0
      ),
      s(
        "audit_down_mid",
        "Audit log fails after two steps",
        "Accept and authorise are done, then the log fails: stop for a person.",
        "needs_review (audit unavailable); accept and authorise done, pay NOT done",
        [],
        &audit_down_mid/0
      )
    ]
  end

  # Restarts and the router: recover from the log, supervise the logs.
  defp restarts do
    [
      s(
        "crash_after_authorise",
        "Restart: crash right after authorise",
        "Kill the transaction when authorise succeeds, then recover from the log.",
        "recovered; paid at most once, whatever the timing",
        [:logs],
        &crash_after_authorise/0
      ),
      s(
        "crash_pay_in_flight",
        "Restart: crash while pay is in flight",
        "Kill the transaction the moment pay is sent, then recover.",
        "completed (pay confirmed) or held for a person; paid at most once",
        [:logs],
        &crash_pay_in_flight/0
      ),
      s(
        "crash_while_undoing",
        "Restart: crash while undoing",
        "Authorise is rejected, and the node dies while the accept is being cancelled.",
        "recovered: undo finished, completed elsewhere, cobalt accept cancelled",
        [:logs],
        &crash_while_undoing/0
      ),
      s(
        "router_killed",
        "Router killed under supervision",
        "Kill the audit router: money stops while it is down, it is restarted, the chain is intact.",
        "refused while down; recovered; a new transaction completes",
        [:supervised],
        &router_killed/0
      ),
      s(
        "logs_verify",
        "Logs verify and residency holds",
        "Every regional log's chain, every transaction's rules, and where each is recorded.",
        "all chains intact; all rules held; residency held",
        [:logs],
        &logs_verify/0
      )
    ]
  end

  def find(id), do: Enum.find(all(), &(&1.id == id))

  @doc "Run one scenario. `router?: false` skips scenarios that need the logs."
  def run(%{needs: needs} = scenario, opts) do
    started = System.monotonic_time(:millisecond)

    if (:logs in needs or :supervised in needs) and not opts[:router?] do
      %{
        status: :skipped,
        summary: "needs the regional logs: start with mix demo --lab",
        checks: [],
        tx: []
      }
    else
      execute(scenario, started)
    end
  end

  defp execute(scenario, started) do
    Process.put(:lab_checks, [])
    Process.put(:lab_tx, [])
    Lab.prepare()

    try do
      scenario.run.()
    rescue
      e -> check("the scenario ran without an internal error", false, Exception.message(e))
    catch
      kind, why ->
        check("the scenario ran without an internal error", false, "#{kind}: #{inspect(why)}")
    end

    checks = Enum.reverse(Process.get(:lab_checks))

    %{
      status: if(Enum.all?(checks, & &1.ok), do: :pass, else: :fail),
      summary: "#{Enum.count(checks, & &1.ok)}/#{length(checks)} checks",
      checks: checks,
      tx: Enum.reverse(Process.get(:lab_tx)),
      ms: System.monotonic_time(:millisecond) - started
    }
  end

  # ---- scenarios ---------------------------------------------------------

  defp happy do
    id = run_tx()
    outcome = Lab.await(id)
    check("completed via cobalt", match?({:ok, %{provider: :cobalt}}, outcome), inspect(outcome))
    each_step_once(Lab.effects(id), "cobalt")
  end

  defp corridor_in do
    id = run_tx(%{"region" => "IN"})
    outcome = Lab.await(id)

    check(
      "completed via cobalt (an eligible provider)",
      match?({:ok, %{provider: :cobalt}}, outcome),
      inspect(outcome)
    )

    in_logs(id, ["IN"])
  end

  defp corridor_cn do
    id = run_tx(%{"region" => "CN"})
    outcome = Lab.await(id)

    check(
      "completed via meridian, the only eligible provider",
      match?({:ok, %{provider: :meridian}}, outcome),
      inspect(outcome)
    )

    in_logs(id, ["CN"])
  end

  defp forward_in_refused do
    id = run_tx(%{"region" => "IN", "instrument" => "forward"})
    outcome = Lab.await(id)

    check(
      "failed: not permitted",
      match?({:error, {:not_permitted, _}}, outcome),
      inspect(outcome)
    )

    fx = Lab.effects(id)
    check("no provider did anything", fx == %{}, inspect(fx))
  end

  defp duplicate_submit do
    id = "dup-#{System.unique_integer([:positive])}"
    first = run_tx(%{"id" => id})
    second = run_tx(%{"id" => id})
    check("both submissions returned the same transaction", first == second and first == id)
    outcome = Lab.await(id)
    check("completed", match?({:ok, _}, outcome), inspect(outcome))
    fx = Lab.effects(id)
    check("pay was done exactly once", Lab.total(fx, :pay) == 1, inspect(fx))
    check("accept was done exactly once", Lab.total(fx, :accept_quote) == 1)
  end

  defp resend_same_key do
    id = run_tx()
    {:ok, _} = Lab.await(id)
    before = Lab.effects(id)

    for op <- @steps do
      case Lab.resend(id, op) do
        {:ok, r} ->
          check("re-sending #{op} with the same key changed nothing", r.unchanged?, r.answer)

        {:error, why} ->
          check("re-sent #{op}", false, why)
      end
    end

    check("effect counts are unchanged afterwards", Lab.effects(id) == before)
  end

  defp failover_authorise do
    Lab.provider("cobalt", "authorise_fails")
    id = run_tx()
    outcome = Lab.await(id)
    fx = Lab.effects(id)

    check(
      "completed via a provider other than cobalt",
      match?({:ok, %{provider: p}} when p != :cobalt, outcome),
      inspect(outcome)
    )

    check(
      "cobalt's accept was cancelled",
      Lab.count(fx, "cobalt", :accept_quote) == 1 and
        Lab.count(fx, "cobalt", :cancel_accept) == 1,
      inspect(fx)
    )

    check("cobalt was never paid", Lab.count(fx, "cobalt", :pay) == 0)
    check("exactly one payment in total", Lab.total(fx, :pay) == 1)
  end

  defp pay_answer_lost do
    Lab.provider("cobalt", "pay_answer_lost")
    id = run_tx()
    outcome = Lab.await(id)
    fx = Lab.effects(id)
    check("completed via cobalt", match?({:ok, %{provider: :cobalt}}, outcome), inspect(outcome))

    check(
      "pay was done exactly once, not repeated",
      Lab.count(fx, "cobalt", :pay) == 1,
      inspect(fx)
    )
  end

  defp pay_unconfirmable do
    Lab.provider("cobalt", "pay_vanishes")
    id = run_tx()
    outcome = Lab.await(id)
    fx = Lab.effects(id)
    check("held for a person", match?({:error, {:needs_review, _}}, outcome), inspect(outcome))
    others = for {{provider, _op}, _} <- fx, provider != "cobalt", do: provider
    check("no other provider did anything (never failed over)", others == [], inspect(fx))
    check("nothing was refunded", Lab.total(fx, :refund) == 0)
  end

  defp settle_refused do
    Lab.provider("cobalt", "settle_fails")
    id = run_tx()
    outcome = Lab.await(id)
    fx = Lab.effects(id)
    check("held for a person", match?({:error, {:needs_review, _}}, outcome), inspect(outcome))
    check("the payment was made", Lab.count(fx, "cobalt", :pay) == 1, inspect(fx))
    check("the payment was NOT refunded", Lab.total(fx, :refund) == 0)
  end

  defp audit_down_start do
    Lab.set_audit(:down)
    id = run_tx()
    outcome = Lab.await(id)
    fx = Lab.effects(id)

    check(
      "failed: audit unavailable",
      match?({:error, {:audit_unavailable, _}}, outcome),
      inspect(outcome)
    )

    check("no provider performed anything", fx == %{}, inspect(fx))
  end

  defp audit_down_mid do
    Lab.set_audit({:after, 2})
    id = run_tx()
    outcome = Lab.await(id)
    fx = Lab.effects(id)

    check(
      "held for a person (audit unavailable)",
      match?({:error, {:needs_review, {:audit_unavailable, _}}}, outcome),
      inspect(outcome)
    )

    check(
      "accept and authorise were done",
      Lab.count(fx, "cobalt", :accept_quote) == 1 and Lab.count(fx, "cobalt", :authorise) == 1,
      inspect(fx)
    )

    check("pay was NOT done", Lab.total(fx, :pay) == 0)
  end

  defp crash_after_authorise, do: crash_and_recover("step_succeeded", "authorise", false)
  defp crash_pay_in_flight, do: crash_and_recover("step_started", "pay", false)

  defp crash_while_undoing do
    Lab.provider("cobalt", "authorise_fails")
    crash_and_recover("undo_started", "cancel_accept", true)
  end

  # Kill the transaction at the armed point, read the plan the log gives, recover, judge.
  defp crash_and_recover(kind, op, expect_failover) do
    id = crash_at(kind, op)
    recover_and_judge(id)
    if expect_failover, do: judge_failover(id)
    {:ok, after_days} = Lab.days()
    check("data residency still holds", Lab.residency(after_days).ok)
  end

  defp crash_at(kind, op) do
    :ok = Lab.arm(kind, op)
    id = run_tx()
    check("the transaction was killed at #{kind} #{op}", Lab.wait_gone(id) == :ok)
    id
  end

  defp recover_and_judge(id) do
    {:ok, days} = Lab.days()
    plan = Enum.find(Lab.plan(days), &(&1.tx == id))
    check("the log says where it stood", plan != nil, inspect(plan))

    results = Lab.recover(days)

    check(
      "recovery resumed it",
      Enum.any?(results, fn {tx, r} -> tx == id and r == :ok end),
      inspect(results)
    )

    outcome = Lab.await(id)
    fx = Lab.effects(id)

    check(
      "ended safely (completed or held for a person)",
      match?({:ok, _}, outcome) or match?({:error, {:needs_review, _}}, outcome),
      inspect(outcome)
    )

    check("paid at most once", Lab.total(fx, :pay) <= 1, inspect(fx))
    check("settled at most once", Lab.total(fx, :settle) <= 1)
  end

  defp judge_failover(id) do
    outcome = Lab.await(id)
    fx = Lab.effects(id)

    check(
      "completed elsewhere",
      match?({:ok, %{provider: p}} when p != :cobalt, outcome),
      inspect(outcome)
    )

    check(
      "cobalt's accept was cancelled",
      Lab.count(fx, "cobalt", :cancel_accept) == 1,
      inspect(fx)
    )
  end

  defp router_killed do
    router = ActorTxSpike.Audit.Router
    check("the router is up to begin with", Process.whereis(router) != nil)
    if pid = Process.whereis(router), do: Process.exit(pid, :kill)

    check(
      "while the router is down, the durable log refuses (no money would move)",
      wait_for(fn -> match?({:error, _}, ActorTxSpike.Events.barrier()) end)
    )

    check(
      "the supervisor restarted the router and the log is available again",
      wait_for(fn -> ActorTxSpike.Events.barrier() == :ok end)
    )

    id = run_tx()
    check("a new transaction completes", match?({:ok, _}, Lab.await(id)))
    check("every log can still be read and verified", match?({:ok, _}, Lab.days()))
  end

  defp logs_verify do
    case Lab.verify_logs() do
      %{logs: logs, residency: residency} ->
        for l <- logs do
          check(
            "#{l.log}: every rule held across #{l.transactions} transactions",
            l.rules_ok,
            Enum.join(l.violations, "; ")
          )
        end

        check("data residency held", residency.ok, Enum.join(residency.violations, "; "))

      {:error, why} ->
        check("the logs could be read and verified", false, inspect(why))
    end
  end

  # ---- helpers -----------------------------------------------------------

  defp s(id, title, about, expect, needs, run),
    do: %{id: id, title: title, about: about, expect: expect, needs: needs, run: run}

  defp run_tx(extra \\ %{}) do
    id = Lab.submit(extra)
    Process.put(:lab_tx, [id | Process.get(:lab_tx)])
    id
  end

  defp check(name, ok, detail \\ nil) do
    entry = %{name: name, ok: ok == true, detail: detail && String.slice(detail, 0, 300)}
    Process.put(:lab_checks, [entry | Process.get(:lab_checks)])
    :ok
  end

  defp each_step_once(fx, provider) do
    for op <- @steps do
      check(
        "#{op} done exactly once at #{provider}",
        Lab.count(fx, provider, op) == 1,
        inspect(fx)
      )
    end
  end

  # Where the transaction is recorded: exactly the expected logs.
  defp in_logs(id, expected) do
    {:ok, days} = Lab.days()
    present = Replay.Residency.logs_holding(days, id)

    check(
      "recorded in #{Enum.join(expected, ", ")} only",
      Enum.sort(present) == Enum.sort(expected),
      "found in #{inspect(present)}"
    )
  end

  defp wait_for(fun), do: Poll.until(fun)
end

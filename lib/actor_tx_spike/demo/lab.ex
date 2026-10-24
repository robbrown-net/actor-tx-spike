defmodule ActorTxSpike.Demo.Lab do
  @moduledoc """
  The "Design for Test" lab behind the dashboard's Test lab view.

  It makes the hard-to-reach behaviours of the system easy to provoke and to
  judge, against the live demo world:

    * **audit log availability**: take the durable log down, or make it fail after
      N steps, to see what each transaction state does (fails cleanly, or stops for
      a person once money has moved)
    * **crashes and restarts**: arm a crash at an exact point in a transaction (the
      transaction process is killed the moment it happens), see the recovery plan
      the log gives (a dry run), then recover
    * **idempotency**: submit the same id twice; re-send a completed step with the
      same key and check the provider did nothing more
    * **the router**: per-region log status, chain verification, the residency
      proof, and killing the supervised router
    * **scenarios**: a library of named scenarios, each with an expected outcome and
      hard checks (provider effect counts, outcomes, residency), run one at a time or
      all together. They double as system-level regression tests.

  The lab owns no domain logic; it drives `ActorTxSpike.Demo` and reads the
  durable logs through `ActorTxSpike.Replay`.
  """
  use GenServer

  alias ActorTxSpike.Audit.Router
  alias ActorTxSpike.{Demo, Events, Poll, Recovery, Replay}
  alias ActorTxSpike.Demo.Lab.Scenarios
  alias ActorTxSpike.Provider.{Fake, Server}
  alias ActorTxSpike.Replay.{Residency, Rules, Tx}

  @short %{accept_quote: "accept", authorise: "authorise", pay: "pay", settle: "settle"}
  @ops Map.keys(@short)

  # ---- API ---------------------------------------------------------------

  def start_link(opts), do: GenServer.start_link(__MODULE__, opts, name: __MODULE__)

  @doc "The durable-log barrier for one transaction, through the lab (so it can be made to fail)."
  @spec barrier_fun(String.t()) :: (-> :ok | {:error, term()})
  def barrier_fun(tx_id), do: fn -> barrier(tx_id) end

  defp barrier(tx_id) do
    GenServer.call(__MODULE__, {:barrier, tx_id}, 20_000)
  catch
    :exit, reason -> {:error, {:lab_unavailable, reason}}
  end

  @doc "Everything the Test lab view shows."
  def snapshot, do: GenServer.call(__MODULE__, :snapshot, 15_000)

  @doc "Run a lab command (see `command/1` clauses). Returns `{:ok, result}` or `{:error, message}`."
  def command(cmd), do: GenServer.call(__MODULE__, {:command, cmd}, 60_000)

  # ---- kit for scenarios (called from the scenario task, not the lab process) --

  @doc "Known starting conditions: price drift off, prices fixed, providers healed, log healthy."
  def prepare do
    Demo.control(%{"action" => "market", "on" => false})
    Demo.control(%{"action" => "pace", "factor" => 0.3})

    for {id, rate} <- [
          cobalt: 1.09,
          bluebird: 1.08,
          northwind: 1.08,
          meridian: 1.07,
          zenith: 1.07
        ] do
      id = Atom.to_string(id)
      Demo.control(%{"action" => "provider", "id" => id, "do" => "heal"})
      Demo.control(%{"action" => "provider", "id" => id, "do" => "reprice", "rate" => rate})
    end

    GenServer.call(__MODULE__, :reset_faults)
  end

  def provider(id, action),
    do: Demo.control(%{"action" => "provider", "id" => id, "do" => action})

  def submit(extra \\ %{}) do
    {:ok, id} = Demo.control(Map.merge(%{"action" => "submit", "amount" => 1_000}, extra))
    id
  end

  def await(id), do: ActorTxSpike.await(id, 20_000)

  def set_audit(mode), do: GenServer.call(__MODULE__, {:audit, mode})
  def arm(kind, op), do: GenServer.call(__MODULE__, {:arm, kind, op})

  @doc "Wait until the transaction's process is gone (reaped after it finishes, or killed)."
  def wait_gone(tx, tries \\ 400) do
    if Poll.until(fn -> not running?(tx) end, tries: tries), do: :ok, else: :timeout
  end

  # A registry lookup, not a call: asking a process that is being killed raises an exit.
  defp running?(tx), do: Registry.lookup(ActorTxSpike.Registry, {:payment, tx}) != []

  @doc "How many times each provider performed each operation for this transaction."
  @spec effects(String.t()) :: %{{String.t(), atom()} => non_neg_integer()}
  def effects(tx) do
    for {id, %{fake: fake}} <- Demo.world().providers,
        {{op, key}, _receipt} <- Fake.effects(fake),
        String.starts_with?(key, tx <> ":"),
        reduce: %{} do
      acc -> Map.update(acc, {to_string(id), op}, 1, &(&1 + 1))
    end
  end

  def count(effects, provider, op), do: Map.get(effects, {provider, op}, 0)
  def total(effects, op), do: for({{_, ^op}, n} <- effects, reduce: 0, do: (acc -> acc + n))

  @doc "Make everything written so far durable, then load every regional log."
  def days, do: days(GenServer.call(__MODULE__, :dir))

  @doc false
  def days(nil), do: {:error, :no_log_directory}

  def days(dir) do
    :ok = Events.barrier()
    Replay.load_dir(dir)
  end

  @doc "Where each open transaction of the loaded logs stood, as plain data."
  def plan(days) do
    days
    |> Map.values()
    |> Enum.flat_map(&Recovery.open/1)
    |> Enum.uniq_by(& &1.tx)
    |> Enum.map(fn p ->
      %{
        tx: p.tx,
        mode: p.mode,
        provider: p.provider,
        op: p.op,
        done: p.done,
        undo: for({undo, original} <- p.undo_queue, do: "#{undo} (of #{original})"),
        running: running?(p.tx)
      }
    end)
  end

  @doc "Resume every open transaction in the logs."
  def recover(days), do: Demo.recover(Map.values(days))

  @doc "Re-send a completed step with the same key to the provider that did it."
  def resend(tx, op), do: resend(tx, op, GenServer.call(__MODULE__, :dir))

  @doc false
  def resend(tx, op, dir) when op in @ops do
    with {:ok, days} <- days(dir),
         {:ok, records} <- records_of(days, tx),
         provider when is_binary(provider) <- Tx.summarize(records).provider,
         %{} = world <- find_provider(provider) do
      {:ok, replay_step(tx, op, provider, Tx.quote_for(records, provider), world)}
    else
      nil -> {:error, "#{tx} did not go through any provider (nothing to re-send)"}
      {:error, _} = error -> error
      _ -> {:error, "no such provider or transaction"}
    end
  end

  # Send a step again, exactly as it was sent, and report whether the provider did anything new.
  defp replay_step(tx, op, provider, quote, %{server: server, fake: fake}) do
    key = "#{tx}:#{provider}:#{@short[op]}"
    before = fake |> Fake.effects() |> Map.get({op, key})
    answer = Server.invoke(server, op, step_args(op, quote, key), 5_000)
    after_ = fake |> Fake.effects() |> Map.get({op, key})

    %{
      tx: tx,
      provider: provider,
      op: op,
      key: key,
      answer: inspect(answer),
      effect_before: inspect(before),
      effect_after: inspect(after_),
      unchanged?: before == after_ and before != nil
    }
  end

  defp step_args(:settle, _quote, key), do: [key]
  defp step_args(_op, quote, key), do: [quote, key]

  @doc "Verify every regional log's chain, every transaction's rules, and data residency."
  def verify_logs, do: verify_logs(GenServer.call(__MODULE__, :dir))

  @doc false
  def verify_logs(dir) do
    with {:ok, days} <- days(dir) do
      %{
        logs:
          for {name, day} <- Enum.sort(days) do
            rules = Rules.verify(day)

            %{
              log: name,
              records: length(day.records),
              transactions: length(day.tx_order),
              rules_ok: rules.ok?,
              violations: for(v <- rules.violations, do: "#{v.rule}: #{v.detail}")
            }
          end,
        residency: residency(days)
      }
    end
  end

  def residency(days) do
    r = Residency.check(days)
    %{ok: r.ok?, transactions: r.transactions, violations: for(v <- r.violations, do: v.detail)}
  end

  # ---- server ------------------------------------------------------------

  @impl true
  def init(opts) do
    {:ok,
     %{
       dir: Keyword.get(opts, :dir),
       supervised: Keyword.get(opts, :supervised, false),
       audit: :ok,
       calls: %{},
       armed: nil,
       crashes: [],
       results: %{},
       running: nil,
       last: %{}
     }}
  end

  @impl true
  def handle_call(:dir, _from, state), do: {:reply, state.dir, state}

  def handle_call(:reset_faults, _from, state) do
    kill_watcher(state.armed)
    {:reply, :ok, %{state | audit: :ok, calls: %{}, armed: nil}}
  end

  def handle_call({:barrier, tx}, _from, state) do
    n = Map.get(state.calls, tx, 0)
    state = %{state | calls: Map.put(state.calls, tx, n + 1)}

    {:reply, barrier_result(state.audit, n), state}
  end

  def handle_call({:audit, mode}, _from, state) do
    Events.emit(nil, :operator, :audit_fault, "test lab: audit log #{describe_audit(mode)}", %{
      mode: inspect(mode)
    })

    {:reply, :ok, %{state | audit: mode, calls: %{}}}
  end

  def handle_call({:arm, kind, op}, _from, state) do
    kill_watcher(state.armed)
    lab = self()
    {:ok, pid} = Task.start(fn -> watch(lab, kind, op) end)
    {:reply, :ok, %{state | armed: %{kind: kind, op: op, pid: pid}}}
  end

  def handle_call(:snapshot, _from, state), do: {:reply, build_snapshot(state), state}

  def handle_call({:command, cmd}, from, state), do: handle_command(cmd, from, state)

  @impl true
  def handle_cast({:crash_fired, tx, kind, op}, state) do
    crash = %{tx: tx, kind: kind, op: op, at: System.system_time(:millisecond)}
    {:noreply, %{state | armed: nil, crashes: Enum.take([crash | state.crashes], 10)}}
  end

  def handle_cast({:result, id, result}, state) do
    {:noreply, %{state | results: Map.put(state.results, id, result)}}
  end

  def handle_cast({:done, _}, state), do: {:noreply, %{state | running: nil}}

  # ---- commands from the screen ------------------------------------------

  defp handle_command(%{"do" => "audit", "mode" => "ok"}, _from, state),
    do: set_audit_cmd(state, :ok)

  defp handle_command(%{"do" => "audit", "mode" => "down"}, _from, state),
    do: set_audit_cmd(state, :down)

  defp handle_command(%{"do" => "audit", "mode" => "after", "steps" => n}, _from, state)
       when is_integer(n) and n >= 0,
       do: set_audit_cmd(state, {:after, n})

  defp handle_command(%{"do" => "arm", "kind" => kind} = cmd, _from, state)
       when is_binary(kind) do
    op = if is_binary(cmd["op"]) and cmd["op"] != "", do: cmd["op"]
    kill_watcher(state.armed)
    lab = self()
    {:ok, pid} = Task.start(fn -> watch(lab, kind, op) end)

    {:reply, {:ok, %{armed: %{kind: kind, op: op}}},
     %{state | armed: %{kind: kind, op: op, pid: pid}}}
  end

  defp handle_command(%{"do" => "disarm"}, _from, state) do
    kill_watcher(state.armed)
    {:reply, {:ok, %{armed: nil}}, %{state | armed: nil}}
  end

  defp handle_command(%{"do" => "kill_router"}, _from, state) do
    case Process.whereis(Router) do
      nil ->
        {:reply, {:error, "no router is running: start with mix demo --lab"}, state}

      pid ->
        Events.emit(nil, :operator, :router_killed, "test lab: killing the audit router", %{})
        Process.exit(pid, :kill)
        {:reply, {:ok, %{killed: inspect(pid), supervised: state.supervised}}, state}
    end
  end

  defp handle_command(%{"do" => "plan"}, _from, state), do: with_days(state, :plan, &plan/1)

  defp handle_command(%{"do" => "recover"}, _from, state) do
    with_days(state, :recovery, fn days ->
      for {tx, result} <- recover(days), do: %{tx: tx, result: inspect(result)}
    end)
  end

  defp handle_command(%{"do" => "residency"}, _from, state),
    do: with_days(state, :residency, &residency/1)

  defp handle_command(%{"do" => "verify"}, _from, state) do
    reply_last(state, :verify, verify_logs(state.dir))
  end

  defp handle_command(%{"do" => "resend", "tx" => tx, "op" => op}, _from, state) do
    op = Enum.find(@ops, &(Atom.to_string(&1) == op))
    resent(op && resend(tx, op, state.dir), state)
  end

  defp handle_command(%{"do" => "duplicate", "tx" => tx}, _from, state)
       when is_binary(tx) and tx != "" do
    first = submit(%{"id" => tx})
    second = submit(%{"id" => tx})
    result = %{id: first, second_submit_returned: second, same_transaction?: first == second}
    {:reply, {:ok, result}, put_last(state, :duplicate, result)}
  end

  defp handle_command(%{"do" => "run", "id" => id}, _from, %{running: nil} = state) do
    lab = self()
    ids = if id == "all", do: Enum.map(Scenarios.all(), & &1.id), else: [id]

    if Enum.all?(ids, &Scenarios.find/1) do
      Task.start(fn -> run_scenarios(lab, ids, state) end)
      {:reply, {:ok, %{running: ids}}, %{state | running: id}}
    else
      {:reply, {:error, "unknown scenario #{id}"}, state}
    end
  end

  defp handle_command(%{"do" => "run"}, _from, state),
    do: {:reply, {:error, "a scenario run is already in progress (#{state.running})"}, state}

  defp handle_command(%{"do" => "clear_results"}, _from, state),
    do: {:reply, {:ok, %{}}, %{state | results: %{}}}

  defp handle_command(other, _from, state),
    do: {:reply, {:error, "unknown lab command #{inspect(Map.get(other, "do"))}"}, state}

  defp set_audit_cmd(state, mode) do
    Events.emit(nil, :operator, :audit_fault, "test lab: audit log #{describe_audit(mode)}", %{
      mode: inspect(mode)
    })

    {:reply, {:ok, %{audit: describe_audit(mode)}}, %{state | audit: mode, calls: %{}}}
  end

  defp with_days(state, key, fun) do
    case days(state.dir) do
      {:ok, days} ->
        result = fun.(days)
        {:reply, {:ok, result}, put_last(state, key, result)}

      {:error, reason} ->
        {:reply, {:error, "cannot read the logs: #{inspect(reason)}"}, state}
    end
  rescue
    e -> {:reply, {:error, Exception.message(e)}, state}
  end

  defp reply_last(state, _key, {:error, reason}),
    do: {:reply, {:error, "cannot read the logs: #{inspect(reason)}"}, state}

  defp reply_last(state, key, result), do: {:reply, {:ok, result}, put_last(state, key, result)}

  defp put_last(state, key, result), do: %{state | last: Map.put(state.last, key, result)}

  # ---- scenarios ---------------------------------------------------------

  # Runs in its own process: scenarios call back into the lab and the demo.
  defp run_scenarios(lab, ids, state) do
    for id <- ids do
      scenario = Scenarios.find(id)
      GenServer.cast(lab, {:result, id, %{status: :running}})
      GenServer.cast(lab, {:result, id, Scenarios.run(scenario, router?: router?(state))})
    end

    prepare()
    GenServer.cast(lab, {:done, ids})
  end

  defp router?(state), do: state.dir != nil and Process.whereis(Router) != nil

  # ---- crash watcher -----------------------------------------------------

  # Kills the transaction process the moment the armed event happens, exactly as a
  # node dying at that instant would: the provider call (if any) may already be on its way.
  defp watch(lab, kind, op) do
    Events.subscribe()
    watch_loop(lab, kind, op)
  end

  defp watch_loop(lab, kind, op) do
    receive do
      {:tx_event, %{tx_id: tx, kind: k, data: data}} when is_binary(tx) ->
        if armed?(k, data, kind, op) and crash(tx) do
          GenServer.cast(lab, {:crash_fired, tx, kind, op})
        else
          watch_loop(lab, kind, op)
        end

      _other ->
        watch_loop(lab, kind, op)
    end
  end

  defp resent({:ok, result}, state), do: {:reply, {:ok, result}, put_last(state, :resend, result)}
  defp resent({:error, _} = error, state), do: {:reply, error, state}

  defp resent(nil, state),
    do: {:reply, {:error, "choose accept_quote, authorise, pay or settle"}, state}

  # What the lab's stand-in for the durable log answers, given the fault it was set to.
  defp barrier_result(:ok, _n), do: Events.barrier()
  defp barrier_result(:down, _n), do: {:error, :audit_down}
  defp barrier_result({:after, limit}, n) when n < limit, do: Events.barrier()
  defp barrier_result({:after, _limit}, _n), do: {:error, :disk_full}

  defp armed?(k, data, kind, op),
    do: to_string(k) == kind and (op == nil or to_string(data[:op]) == op)

  defp crash(tx) do
    case Registry.lookup(ActorTxSpike.Registry, {:payment, tx}) do
      [{pid, _}] ->
        Events.emit(tx, :operator, :crash_injected, "test lab: killing #{tx} right here", %{})
        Process.exit(pid, :kill)
        true

      [] ->
        false
    end
  end

  defp kill_watcher(%{pid: pid}), do: Process.exit(pid, :kill)
  defp kill_watcher(nil), do: :ok

  # ---- snapshot ----------------------------------------------------------

  defp build_snapshot(state) do
    %{
      router: router_status(state),
      audit: %{mode: describe_audit(state.audit), mode_key: audit_key(state.audit)},
      armed: state.armed && Map.take(state.armed, [:kind, :op]),
      crashes: state.crashes,
      effects: effects_table(),
      scenarios:
        for s <- Scenarios.all() do
          Map.merge(
            %{id: s.id, title: s.title, about: s.about, expect: s.expect, needs: s.needs},
            Map.get(state.results, s.id, %{status: :not_run})
          )
        end,
      running: state.running,
      last: state.last
    }
  end

  defp router_status(%{dir: nil}), do: %{up: false, dir: nil, supervised: false, logs: []}

  defp router_status(state) do
    up = Process.whereis(Router) != nil

    logs =
      if up do
        for {name, head} <- Router.heads() |> Enum.sort(),
            do: %{name: name, records: head.records, head: String.slice(head.head, 0, 10)}
      else
        []
      end

    %{up: up, dir: state.dir, supervised: state.supervised, logs: logs}
  end

  defp effects_table do
    for {id, %{fake: fake}} <- Demo.world().providers |> Enum.sort(),
        {op, n} <-
          fake
          |> Fake.effects()
          |> Enum.frequencies_by(fn {{op, _key}, _} -> op end)
          |> Enum.sort() do
      %{provider: to_string(id), op: op, count: n}
    end
  end

  defp describe_audit(:ok), do: "healthy"
  defp describe_audit(:down), do: "DOWN (every step refused)"
  defp describe_audit({:after, n}), do: "fails after #{n} step(s) of each transaction"

  defp audit_key(:ok), do: "ok"
  defp audit_key(:down), do: "down"
  defp audit_key({:after, n}), do: "after:#{n}"

  defp records_of(days, tx) do
    days
    |> Map.values()
    |> Enum.find_value(fn day -> day.by_tx[tx] end)
    |> case do
      nil -> {:error, "#{tx} is not in any log"}
      records -> {:ok, records}
    end
  end

  defp find_provider(name) do
    Enum.find_value(Demo.world().providers, fn {id, p} -> if to_string(id) == name, do: p end)
  end
end

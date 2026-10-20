defmodule ActorTxSpike.Metrics do
  @moduledoc """
  Resource and application metrics: the "how much does it cost" half of
  observability (the narrative in `ActorTxSpike.Events` is the "what and why").

  A single sampler ticks every `:interval_ms` (default 1 s) and records:

  * memory by category, VM CPU share, scheduler utilisation, run-queue length,
    reductions/s, process and port counts, GC count
  * network bytes/s over TCP sockets and open connection count
  * top processes by memory and mailbox length (every 5th sample)
  * application throughput by outcome, failovers, end-to-end latency
    percentiles, and the latest health of each provider (from the narrative)
  * its own overhead (time spent sampling)

  Tiers (see docs/architecture/07-observability-and-performance.md): a raw ring
  of the last `:raw_capacity` samples and a ring of one-minute rollups. Both are
  bounded, so monitoring cannot become the leak.
  """
  use GenServer

  alias ActorTxSpike.Events
  alias ActorTxSpike.Metrics.{Rollup, Stats, VM}

  @top_every 5
  @e2e_window 500
  @terminal %{tx_completed: :completed, tx_failed: :failed, needs_review: :needs_review}

  def start_link(opts) do
    GenServer.start_link(__MODULE__, opts, name: Keyword.get(opts, :name, __MODULE__))
  end

  @doc "Take a sample now (also what the timer does)."
  def sample(server \\ __MODULE__), do: GenServer.call(server, :sample)

  @doc "Raw samples (oldest first, at most `limit`), minute rollups and the latest sample."
  def snapshot(server \\ __MODULE__, limit \\ 300), do: GenServer.call(server, {:snapshot, limit})

  @impl true
  def init(opts) do
    VM.enable_scheduler_accounting()
    interval = Keyword.get(opts, :interval_ms, 1_000)
    if is_integer(interval), do: :timer.send_interval(interval, :tick)
    # `subscribe: false` lets tests feed events by hand, isolated from the global stream.
    if Keyword.get(opts, :subscribe, true), do: Events.subscribe()

    {:ok,
     %{
       raw: :queue.new(),
       raw_size: 0,
       raw_capacity: Keyword.get(opts, :raw_capacity, 900),
       minutes: :queue.new(),
       minute_capacity: Keyword.get(opts, :minute_capacity, 1_440),
       bucket: nil,
       prev: nil,
       clock: Keyword.get(opts, :clock, fn -> System.system_time(:millisecond) end),
       count: 0,
       top: [],
       starts: %{},
       durations: [],
       counts: %{completed: 0, failed: 0, needs_review: 0, started: 0, failovers: 0},
       providers: %{}
     }}
  end

  @impl true
  def handle_call(:sample, _from, state) do
    {sample, state} = take_sample(state)
    {:reply, sample, state}
  end

  def handle_call({:snapshot, limit}, _from, state) do
    raw = state.raw |> :queue.to_list() |> Enum.take(-limit)

    {:reply,
     %{
       raw: raw,
       minutes: :queue.to_list(state.minutes),
       latest: List.last(raw),
       interval_ms: 1_000
     }, state}
  end

  @impl true
  def handle_info(:tick, state) do
    {_sample, state} = take_sample(state)
    {:noreply, state}
  end

  def handle_info({:tx_event, event}, state), do: {:noreply, track(event, state)}

  # ---- application counters from the narrative -------------------------------

  defp track(%{actor: :payment, kind: :tx_received, tx_id: tx, at_ms: at}, state) do
    %{state | starts: Map.put(state.starts, tx, at), counts: bump(state.counts, :started)}
  end

  defp track(%{actor: :payment, kind: kind, tx_id: tx, at_ms: at}, state)
       when is_map_key(@terminal, kind) do
    durations = record_duration(state.durations, Map.fetch(state.starts, tx), at)

    %{
      state
      | starts: Map.delete(state.starts, tx),
        durations: durations,
        counts: bump(state.counts, Map.fetch!(@terminal, kind))
    }
  end

  defp track(%{actor: :payment, kind: :failover}, state),
    do: %{state | counts: bump(state.counts, :failovers)}

  defp track(%{kind: :health, data: %{provider: id} = data}, state) do
    info = Map.take(data, [:state, :p95_ms, :error_rate, :in_flight, :breaker])
    %{state | providers: Map.put(state.providers, id, info)}
  end

  defp track(_event, state), do: state

  defp record_duration(durations, {:ok, started}, at),
    do: Enum.take([at - started | durations], @e2e_window)

  defp record_duration(durations, :error, _at), do: durations

  defp bump(counts, key), do: Map.update!(counts, key, &(&1 + 1))

  # ---- sampling --------------------------------------------------------------

  defp take_sample(state) do
    t0 = System.monotonic_time(:microsecond)
    cur = reading(state)
    prev = state.prev || cur
    top = if rem(state.count, @top_every) == 0, do: VM.top_processes(5), else: state.top

    sample =
      cur
      |> rates(prev)
      |> Map.merge(%{
        memory: VM.memory(),
        run_queue: VM.run_queue(),
        processes: VM.process_count(),
        ports: VM.port_count(),
        sockets: cur.sockets,
        top: top,
        tx: tx_deltas(cur.counts, prev.counts, map_size(state.starts)),
        e2e: Stats.summary(state.durations),
        providers: state.providers
      })

    sample = Map.put(sample, :overhead_us, System.monotonic_time(:microsecond) - t0)
    {sample, store(%{state | prev: cur, count: state.count + 1, top: top}, sample)}
  end

  # The raw cumulative counters of the VM right now.
  defp reading(state) do
    {active, total} = VM.scheduler_wall_time()
    {recv, send, sockets} = VM.tcp_bytes()

    %{
      at: state.clock.(),
      active: active,
      total: total,
      recv: recv,
      send: send,
      sockets: sockets,
      cpu_ms: VM.cpu_ms(),
      reductions: VM.reductions(),
      gc: VM.gc_count(),
      counts: state.counts
    }
  end

  # Per-second rates between two readings.
  defp rates(cur, prev) do
    dt = max(cur.at - prev.at, 1) / 1_000

    %{
      at: cur.at,
      cpu: Float.round(min((cur.cpu_ms - prev.cpu_ms) / (dt * 1_000), 64.0), 3),
      sched: scheduler_utilisation(cur, prev),
      reductions_ps: round((cur.reductions - prev.reductions) / dt),
      gc_ps: round((cur.gc - prev.gc) / dt),
      net_in_bps: round((cur.recv - prev.recv) / dt),
      net_out_bps: round((cur.send - prev.send) / dt)
    }
  end

  defp scheduler_utilisation(cur, prev) when cur.total > prev.total,
    do: Float.round((cur.active - prev.active) / (cur.total - prev.total), 3)

  defp scheduler_utilisation(_cur, _prev), do: 0.0

  defp tx_deltas(cur, prev, in_flight) do
    delta = fn key -> Map.fetch!(cur, key) - Map.fetch!(prev, key) end

    %{
      started: delta.(:started),
      completed: delta.(:completed),
      failed: delta.(:failed),
      needs_review: delta.(:needs_review),
      failovers: delta.(:failovers),
      in_flight: in_flight
    }
  end

  defp store(state, sample) do
    {raw, raw_size} = push(state.raw, state.raw_size, state.raw_capacity, sample)
    roll(%{state | raw: raw, raw_size: raw_size}, sample)
  end

  # Fold the sample into the minute bucket it belongs to, closing the previous minute if it ended.
  defp roll(%{bucket: nil} = state, sample), do: %{state | bucket: Rollup.add(nil, sample)}

  defp roll(%{bucket: %{at: at} = bucket} = state, sample) do
    if Rollup.minute(sample.at) == at do
      %{state | bucket: Rollup.add(bucket, sample)}
    else
      minutes = push_minute(state.minutes, state.minute_capacity, Rollup.finalize(bucket))
      %{state | minutes: minutes, bucket: Rollup.add(nil, sample)}
    end
  end

  defp push(queue, size, capacity, item) when size >= capacity do
    {_dropped, queue} = :queue.out(queue)
    {:queue.in(item, queue), size}
  end

  defp push(queue, size, _capacity, item), do: {:queue.in(item, queue), size + 1}

  defp push_minute(queue, capacity, item) do
    queue = :queue.in(item, queue)
    if :queue.len(queue) > capacity, do: elem(:queue.out(queue), 1), else: queue
  end
end
